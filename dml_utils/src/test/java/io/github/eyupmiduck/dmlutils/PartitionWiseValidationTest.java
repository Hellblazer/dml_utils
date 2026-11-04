package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMulti.TEST_PARTITIONED_MULTI;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies partition-wise eligibility and safety checks: partition-wise ordered
 * keys resolve the parent index and every leaf's child index (including a
 * multi-level tree), an index that only exists on the parent is rejected before
 * a run row, and losing a leaf's UPDATE privilege during processing fails
 * closed while keeping earlier progress and allowing a retry.
 */
class PartitionWiseValidationTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Empties the fixtures between tests.
     */
    @BeforeEach
    void reset() {
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
        dsl.truncate(TEST_PARTITIONED_MULTI).execute();
    }

    /**
     * A partition-wise ordered key on a range parent resolves the parent index
     * descriptor and processes every leaf.
     */
    @Test
    void orderedKeyRunProcessesEveryLeaf() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("validation-ordered-range");

        run(PARTITIONED_RANGE, label, 2, ChunkingStrategy.ordered_key,
                "test_partitioned_range_ordered_key_idx");

        MigrationRunRecord run = run(label);
        assertEquals(ChunkingStrategy.ordered_key, run.getChunkBy());
        assertTrue(run.getPartitionWise());
        assertNotNull(run.getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A multi-level partitioned unique index validates every storage leaf's child
     * index through the recursive index tree, and the run completes.
     */
    @Test
    void multiLevelOrderedKeyRunProcessesEveryLeaf() {
        dsl.insertInto(TEST_PARTITIONED_MULTI,
                        TEST_PARTITIONED_MULTI.ID, TEST_PARTITIONED_MULTI.BUCKET,
                        TEST_PARTITIONED_MULTI.PAYLOAD)
                .values(0L, 0, "pending")
                .values(0L, 1, "pending")
                .values(100L, 0, "pending")
                .execute();
        String label = uniqueLabel("validation-ordered-multi");
        String index = "test_partitioned_multi_ordered_key_idx";

        assertArrayEquals(new String[]{"id", "bucket"},
                Routines.partitionedOrderedKeyColumns(dsl.configuration(),
                        PARTITION_SCHEMA, "test_partitioned_multi", index));
        assertArrayEquals(new String[]{"bigint", "bigint"},
                Routines.partitionedOrderedKeyKinds(dsl.configuration(),
                        PARTITION_SCHEMA, "test_partitioned_multi", index));

        run("test_partitioned_multi", label, 2, ChunkingStrategy.ordered_key, index);

        assertNotNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_MULTI,
                TEST_PARTITIONED_MULTI.PAYLOAD.ne("done")));
    }

    /**
     * An index created with ON ONLY is invalid, so the parent-index validation
     * fails with 55000 before a run row is written.
     */
    @Test
    void rejectsAnIndexWithNoChildIndexes() throws Exception {
        insertRangeRows(0L);
        String index = "test_partitioned_range_only_idx";
        String label = uniqueLabel("validation-only-index");
        executeAsOwner("CREATE UNIQUE INDEX " + index + " ON ONLY "
                + PARTITION_SCHEMA + "." + PARTITIONED_RANGE + " (id)");
        try {
            assertSqlState("55000", () -> run(PARTITIONED_RANGE, label, 2,
                    ChunkingStrategy.ordered_key, index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
        }

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "no run row should be created");
    }

    /**
     * An index that belongs to another table is rejected with undefined_object.
     */
    @Test
    void rejectsAnIndexOnAnotherTable() {
        insertRangeRows(0L);
        String label = uniqueLabel("validation-wrong-index");

        assertSqlState("42704", () -> run(PARTITIONED_RANGE, label, 2,
                ChunkingStrategy.ordered_key, "test_partitioned_id_idx"));

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)));
    }

    /**
     * A leaf that loses UPDATE after calculation fails the chunk with 42501,
     * keeps earlier chunks committed, and completes after the grant is restored.
     */
    @Test
    void lostUpdatePrivilegeDuringProcessingFailsClosedAndResumes() throws Exception {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("validation-lost-update");
        long runId = populate(PARTITIONED_RANGE, label, 1, ChunkingStrategy.primary_key);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);
        String highLeaf = PARTITION_SCHEMA + ".test_partitioned_range_high";

        executeAsOwner("REVOKE UPDATE ON " + highLeaf + " FROM "
                + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> run(PARTITIONED_RANGE, label, 1,
                    ChunkingStrategy.primary_key, null));
        } finally {
            executeAsOwner("GRANT UPDATE ON " + highLeaf + " TO "
                    + TEST_USER + ", dml_utils_caller");
        }

        MigrationRunRecord failed = run(label);
        assertNull(failed.getCompletedAt(), "the run stays incomplete after a failed chunk");
        assertTrue(failed.getTotalChunksCompleted() > 0,
                "earlier chunks should stay committed");
        assertTrue(failed.getTotalChunksCompleted() < failed.getTotalChunks());

        run(PARTITIONED_RANGE, label, 1, ChunkingStrategy.primary_key, null);

        assertNotNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A resumed partition-wise ordered run fails closed when the stored parent
     * index disappears, and completes after it is recreated.
     */
    @Test
    void resumeFailsClosedWhenTheParentIndexIsDropped() throws Exception {
        insertRangeRows(0L, 100L, 200L);
        String label = uniqueLabel("validation-resume-index");
        String parentIndex = "test_partitioned_range_ordered_key_idx";
        long runId = populateOrdered(label, parentIndex);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        executeAsOwner("DROP INDEX " + PARTITION_SCHEMA + "." + parentIndex);
        try {
            assertSqlState("55000", () -> run(PARTITIONED_RANGE, label, 2,
                    ChunkingStrategy.ordered_key, parentIndex));
        } finally {
            executeAsOwner("CREATE UNIQUE INDEX " + parentIndex + " ON "
                    + PARTITION_SCHEMA + "." + PARTITIONED_RANGE + " (id)");
        }

        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.PAYLOAD.eq("done")),
                "a failed resume must not process a chunk");

        run(PARTITIONED_RANGE, label, 2, ChunkingStrategy.ordered_key, parentIndex);
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A manifest leaf that has no matching child index is rejected with 0A000.
     * PostgreSQL keeps the live tree and its indexes consistent, so this guard is
     * exercised with a synthetic manifest row.
     */
    @Test
    void leafValidationRejectsAManifestLeafWithoutAChildIndex() {
        insertRangeRows(0L);
        String label = uniqueLabel("validation-synthetic-leaf");
        String index = "test_partitioned_range_ordered_key_idx";
        long runId = populateOrdered(label, index);

        dsl.insertInto(MIGRATION_RUN_LEAF)
                .columns(MIGRATION_RUN_LEAF.RUN_ID, MIGRATION_RUN_LEAF.LEAF_ORDINAL,
                        MIGRATION_RUN_LEAF.LEAF_SCHEMA_NAME, MIGRATION_RUN_LEAF.LEAF_NAME,
                        MIGRATION_RUN_LEAF.LEAF_OID, MIGRATION_RUN_LEAF.LEAF_BOUND,
                        MIGRATION_RUN_LEAF.STATE)
                .values(runId, 99, PARTITION_SCHEMA, "no_such_leaf", 1L,
                        "FOR VALUES FROM (1000) TO (2000)", "pending")
                .execute();

        assertSqlState("0A000", () -> Routines.assertPartitionedOrderedKeyLeaves(
                dsl.configuration(), runId, index));
    }

    /**
     * The leaf validator rejects a run that is not a partition-wise ordered-key
     * run.
     */
    @Test
    void leafValidationRejectsANonPartitionedRun() {
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(), PARTITION_SCHEMA,
                "test_bigint", uniqueLabel("validation-plain-leaf"), "SELECT 1", 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false);

        assertSqlState("22023", () -> Routines.assertPartitionedOrderedKeyLeaves(
                dsl.configuration(), runId, "ignored"));
    }

    /**
     * A resumed partition-wise ordered run fails closed with 55000 when the parent
     * index is recreated with a different key descriptor.
     */
    @Test
    void resumeFailsWhenTheStoredDescriptorChanges() throws Exception {
        dsl.insertInto(TEST_PARTITIONED_MULTI,
                        TEST_PARTITIONED_MULTI.ID, TEST_PARTITIONED_MULTI.BUCKET,
                        TEST_PARTITIONED_MULTI.PAYLOAD)
                .values(0L, 0, "pending")
                .execute();
        String label = uniqueLabel("validation-descriptor-change");
        String index = "test_partitioned_multi_ordered_key_idx";
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(), PARTITION_SCHEMA,
                "test_partitioned_multi", label, TEMPLATE, 2, 1,
                ChunkingStrategy.ordered_key, "t", 0, index, true);
        assertNotNull(run(label));

        executeAsOwner("DROP INDEX " + PARTITION_SCHEMA + "." + index);
        executeAsOwner("CREATE UNIQUE INDEX " + index + " ON "
                + PARTITION_SCHEMA + ".test_partitioned_multi (bucket, id)");
        try {
            assertSqlState("55000", () -> run("test_partitioned_multi", label, 2,
                    ChunkingStrategy.ordered_key, index));
        } finally {
            executeAsOwner("DROP INDEX " + PARTITION_SCHEMA + "." + index);
            executeAsOwner("CREATE UNIQUE INDEX " + index + " ON "
                    + PARTITION_SCHEMA + ".test_partitioned_multi (id, bucket)");
        }

        assertNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_MULTI,
                        TEST_PARTITIONED_MULTI.PAYLOAD.eq("done")),
                "a mismatched resume must not process a chunk");
        assertTrue(runId > 0, "populateMigrationBoundaries should return the new run id");
    }


    private long populateOrdered(String label, String index) {
        return Routines.populateMigrationBoundaries(dsl.configuration(), PARTITION_SCHEMA,
                PARTITIONED_RANGE, label, TEMPLATE, 2, 1, ChunkingStrategy.ordered_key,
                "t", 0, index, true);
    }

    private long populate(String table, String label, int chunkSize, ChunkingStrategy chunkBy) {
        return Routines.populateMigrationBoundaries(dsl.configuration(), PARTITION_SCHEMA,
                table, label, TEMPLATE, chunkSize, 1, chunkBy, "t", 0, null, true);
    }

    private void run(String table, String label, int chunkSize, ChunkingStrategy chunkBy,
                     String boundaryKeyIndex) {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, PARTITION_SCHEMA, table,
                label, chunkSize, 1, "t", chunkBy, 1, 0, boundaryKeyIndex, true);
    }

    private MigrationRunRecord run(String label) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne();
    }
}
