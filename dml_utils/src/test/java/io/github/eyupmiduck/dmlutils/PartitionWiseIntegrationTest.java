package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainChunkPlanRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedHash.TEST_PARTITIONED_HASH;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedList.TEST_PARTITIONED_LIST;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMulti.TEST_PARTITIONED_MULTI;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end partition-wise coverage across the range, list, hash and multi-level
 * fixtures: primary-key and block runs, single- and multi-threaded execution, a
 * final partial chunk, attach/detach between runs, a deterministic
 * mid-calculation failure and resume, and an EXPLAIN that prunes to one leaf.
 */
class PartitionWiseIntegrationTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";
    private static final String LIST = "test_partitioned_list";
    private static final String MULTI = "test_partitioned_multi";
    private static final String HASH = "test_partitioned_hash";

    /**
     * Empties the partition fixtures between tests.
     */
    @BeforeEach
    void reset() {
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
        dsl.truncate(TEST_PARTITIONED_HASH).execute();
        dsl.truncate(TEST_PARTITIONED_LIST).execute();
        dsl.truncate(TEST_PARTITIONED_MULTI).execute();
    }

    /**
     * A hash parent with no rows finalizes to zero chunks with every leaf
     * calculated.
     */
    @Test
    void hashPartitionedAllEmptyRunFinalizesToZero() {
        String label = uniqueLabel("integration-hash-empty");

        run(HASH, label, 2, 1, ChunkingStrategy.primary_key, null);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCompletedAt());
        assertEquals(0L, run.getTotalChunks());
        assertEquals(2, leaves(run.getRunId()).size());
        for (MigrationRunLeafRecord leaf : leaves(run.getRunId())) {
            assertEquals("calculated", leaf.getState());
            assertEquals(0L, leaf.getBoundaryCount());
        }
    }

    /**
     * A hash parent processes rows from its leaves.
     */
    @Test
    void hashPartitionedRunProcessesRows() {
        for (long id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_PARTITIONED_HASH,
                            TEST_PARTITIONED_HASH.ID, TEST_PARTITIONED_HASH.PAYLOAD)
                    .values(id, "pending")
                    .execute();
        }
        String label = uniqueLabel("integration-hash");

        run(HASH, label, 2, 1, ChunkingStrategy.primary_key, null);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCompletedAt());
        assertTrue(run.getTotalChunks() > 0);
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_HASH,
                TEST_PARTITIONED_HASH.PAYLOAD.ne("done")));
        assertBoundariesCoverTheRun(run);
    }

    /**
     * A list parent processes rows from both list leaves.
     */
    @Test
    void listPartitionedRunProcessesRows() {
        dsl.insertInto(TEST_PARTITIONED_LIST, TEST_PARTITIONED_LIST.ID,
                        TEST_PARTITIONED_LIST.PAYLOAD)
                .values(1, "pending")
                .values(2, "pending")
                .values(3, "pending")
                .execute();
        String label = uniqueLabel("integration-list");

        run(LIST, label, 2, 1, ChunkingStrategy.primary_key, null);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCompletedAt());
        // leaf [1,2] has one start; leaf [3] has one start; plus the terminal.
        assertEquals(2L, run.getTotalChunks());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_LIST,
                TEST_PARTITIONED_LIST.PAYLOAD.ne("done")));
        assertBoundariesCoverTheRun(run);
    }

    /**
     * A multi-level parent expands its storage leaves and processes rows from both
     * levels.
     */
    @Test
    void multiLevelPrimaryKeyRunProcessesRows() {
        dsl.insertInto(TEST_PARTITIONED_MULTI,
                        TEST_PARTITIONED_MULTI.ID, TEST_PARTITIONED_MULTI.BUCKET,
                        TEST_PARTITIONED_MULTI.PAYLOAD)
                .values(0L, 0, "pending")
                .values(0L, 1, "pending")
                .values(100L, 0, "pending")
                .execute();
        String label = uniqueLabel("integration-multi");

        run(MULTI, label, 2, 1, ChunkingStrategy.primary_key, null);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCompletedAt());
        assertEquals(leaves(run.getRunId()).stream()
                        .mapToLong(MigrationRunLeafRecord::getBoundaryCount).sum(),
                run.getTotalChunks(),
                "the flattened total is the sum of the per-leaf starts");
        assertTrue(run.getTotalChunks() > 0);
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_MULTI,
                TEST_PARTITIONED_MULTI.PAYLOAD.ne("done")));
        assertBoundariesCoverTheRun(run);
    }

    /**
     * A blocks run over a hash parent records one filepath per leaf and processes
     * each leaf's heap.
     */
    @Test
    void blocksRunAcrossHashLeaves() {
        for (long id = 1; id <= 10; id++) {
            dsl.insertInto(TEST_PARTITIONED_HASH,
                            TEST_PARTITIONED_HASH.ID, TEST_PARTITIONED_HASH.PAYLOAD)
                    .values(id, "pending")
                    .execute();
        }
        String label = uniqueLabel("integration-hash-blocks");

        run(HASH, label, 1, 1, ChunkingStrategy.blocks, null);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCompletedAt());
        assertNull(run.getDrivingTableRelationFilepath());
        assertTrue(run.getTotalChunks() > 0);
        for (MigrationRunLeafRecord leaf : leaves(run.getRunId())) {
            assertNotNull(leaf.getRelationFilepath());
        }
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_HASH,
                TEST_PARTITIONED_HASH.PAYLOAD.ne("done")));
    }

    /**
     * A multi-threaded run keeps the flattened sequence contiguous and completes
     * every chunk exactly once.
     */
    @Test
    void multiThreadedRunCompletesEveryChunkOnce() {
        for (long base : new long[]{0, 100, 200}) {
            for (long offset = 0; offset < 20; offset++) {
                dsl.insertInto(TEST_PARTITIONED_RANGE,
                                TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                        .values(base + offset, "pending")
                        .execute();
            }
        }
        String label = uniqueLabel("integration-threads");

        run(PARTITIONED_RANGE, label, 3, 4, ChunkingStrategy.primary_key, null);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCompletedAt());
        // 20 rows per leaf, chunk 3 -> 7 starts per leaf, plus the terminal.
        assertEquals(21L, run.getTotalChunks());
        assertEquals(21L, run.getTotalChunksCompleted());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L,
                        12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L),
                runBoundaries(run.getRunId()).stream()
                        .map(MigrationBoundaryRecord::getBoundaryNo).toList());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A completed run, a detach/attach, and a new run all succeed; the new run
     * snapshots the restored tree.
     */
    @Test
    void newRunAfterDetachAndReattach() throws Exception {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(0L, "pending").values(100L, "pending").values(200L, "pending")
                .execute();
        String firstLabel = uniqueLabel("integration-detach-first");
        run(PARTITIONED_RANGE, firstLabel, 2, 1, ChunkingStrategy.primary_key, null);
        assertNotNull(run(firstLabel).getCompletedAt());

        executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE
                + " DETACH PARTITION " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID);
        try {
            executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE
                    + " ATTACH PARTITION " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID
                    + " FOR VALUES FROM (100) TO (200)");
        } finally {
            restoreMidPartition();
        }

        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(101L, "pending")
                .execute();
        String secondLabel = uniqueLabel("integration-detach-second");
        run(PARTITIONED_RANGE, secondLabel, 2, 1, ChunkingStrategy.primary_key, null);

        assertNotNull(run(secondLabel).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A forced failure while calculating leaf 2 keeps leaf 1's committed
     * boundaries and manifest row, leaves the run unfinalized, and a retry resumes
     * at leaf 2 without recomputing leaf 1.
     */
    @Test
    void midCalculationFailurePreservesAndResumes() throws Exception {
        for (long base : new long[]{0, 100, 200}) {
            dsl.insertInto(TEST_PARTITIONED_RANGE,
                            TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(base, "pending")
                    .values(base + 1, "pending")
                    .execute();
        }
        String label = uniqueLabel("integration-mid-failure");
        long runId = populate(PARTITIONED_RANGE, label, 1);

        executeAsOwner("CREATE OR REPLACE FUNCTION " + PARTITION_SCHEMA + ".fail_leaf_two()"
                + " RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'injected leaf failure' USING ERRCODE = '55000';"
                + " RETURN NEW; END; $$");
        executeAsOwner("CREATE TRIGGER fail_leaf_two BEFORE INSERT"
                + " ON dml_utils_data.migration_boundary FOR EACH ROW"
                + " WHEN (NEW.leaf_ordinal = 2)"
                + " EXECUTE FUNCTION " + PARTITION_SCHEMA + ".fail_leaf_two()");
        try {
            assertSqlState("55000", () -> run(PARTITIONED_RANGE, label, 1));
        } finally {
            executeAsOwner("DROP TRIGGER IF EXISTS fail_leaf_two"
                    + " ON dml_utils_data.migration_boundary");
            executeAsOwner("DROP FUNCTION IF EXISTS " + PARTITION_SCHEMA + ".fail_leaf_two()");
        }

        List<MigrationRunLeafRecord> leaves = leaves(runId);
        assertEquals("calculated", leaves.get(0).getState());
        long firstLeafBoundaries = leaves.get(0).getBoundaryCount();
        assertTrue(firstLeafBoundaries > 0);
        assertEquals("failed", leaves.get(1).getState());
        assertEquals("55000", leaves.get(1).getErrorSqlstate());
        assertEquals("pending", leaves.get(2).getState());
        assertNull(run(label).getBoundariesCalculatedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.eq("done")));

        run(PARTITIONED_RANGE, label, 1);

        assertEquals(firstLeafBoundaries, leaves(runId).get(0).getBoundaryCount(),
                "the calculated leaf must not be recomputed");
        assertNotNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * An EXPLAIN of a real leaf chunk plans only that leaf, not the parent.
     */
    @Test
    void explainPrunesToTheLeaf() {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(0L, "pending").values(1L, "pending")
                .values(100L, "pending").values(200L, "pending")
                .execute();
        String label = uniqueLabel("integration-explain");
        long runId = populate(PARTITIONED_RANGE, label, 1);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        List<MigrationBoundaryRecord> boundaries = runBoundaries(runId);
        MigrationBoundaryRecord first = boundaries.getFirst();
        MigrationBoundaryRecord next = boundaries.get(1);
        String leafName = leaves(runId).get(first.getLeafOrdinal() - 1).getLeafName();
        String[] kinds = {"bigint"};
        String[] start = Routines.migrationKeyValues(dsl.configuration(),
                first.getBoundaryId(), kinds);
        String[] end = Routines.migrationKeyValues(dsl.configuration(),
                next.getBoundaryId(), kinds);

        ExplainChunkPlanRecord plan = Routines.explainChunkPlan(
                dsl.configuration(), TEMPLATE, PARTITION_SCHEMA, leafName, "t",
                new String[]{"id"}, kinds, start, end, false).getFirst();

        assertTrue(plan.getOSqlText().contains(PARTITION_SCHEMA + "." + leafName + " t"));
        assertEquals("ModifyTable", planNodeType(plan.getOPlan()));
        assertTrue(plan.getOPlan().data().contains(leafName),
                "the plan should name the leaf: " + plan.getOPlan().data());
        assertFalse(plan.getOPlan().data().contains("Append"),
                "the plan must not append the parent's partitions");
    }

    private void assertBoundariesCoverTheRun(MigrationRunRecord run) {
        List<MigrationBoundaryRecord> boundaries = runBoundaries(run.getRunId());
        assertEquals(run.getTotalChunks() + 1, boundaries.size());
        for (int index = 0; index < boundaries.size(); index++) {
            assertEquals(index, boundaries.get(index).getBoundaryNo());
            assertNotNull(boundaries.get(index).getLeafOrdinal());
        }
    }

    private long populate(String table, String label, int chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(), PARTITION_SCHEMA,
                table, label, TEMPLATE, chunkSize, 1, ChunkingStrategy.primary_key,
                "t", 0, null, true);
    }

    private void run(String table, String label, int chunkSize) {
        run(table, label, chunkSize, 1, ChunkingStrategy.primary_key, null);
    }

    private void run(String table, String label, int chunkSize, int threads,
                     ChunkingStrategy chunkBy, String boundaryKeyIndex) {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, PARTITION_SCHEMA, table, label,
                chunkSize, threads, "t", chunkBy, 1, 0, boundaryKeyIndex, true);
    }

    private MigrationRunRecord run(String label) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne();
    }

    private List<MigrationRunLeafRecord> leaves(long runId) {
        return dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .orderBy(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .fetch();
    }

    private List<MigrationBoundaryRecord> runBoundaries(long runId) {
        return dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
    }
}
