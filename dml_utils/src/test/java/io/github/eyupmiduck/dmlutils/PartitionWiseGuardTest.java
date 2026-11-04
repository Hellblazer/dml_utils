package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the partition-wise boundary guards and calculation entry point: the
 * live-tree comparison for a renamed leaf, a rewritten blocks heap, a
 * single-relation run and a missing run, plus the calculation's rejection and
 * idempotence behaviour and the run lock key.
 */
class PartitionWiseGuardTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_partitioned_range";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Empties the range fixture between tests.
     */
    @BeforeEach
    void reset() {
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A same-count identity change (a renamed leaf) is detected by the per-leaf
     * comparison, not only by a leaf-count change.
     */
    @Test
    void treeGuardRejectsARenamedLeaf() throws Exception {
        long runId = populate(TABLE, uniqueLabel("guard-rename"), 2,
                ChunkingStrategy.primary_key, true);

        executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_range_mid"
                + " RENAME TO test_partitioned_range_mid_renamed");
        try {
            assertSqlState("55000", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertMigrationTreeUnchanged(
                    dsl.configuration(), runId));
        } finally {
            if (relationExists("test_partitioned_range_mid_renamed")) {
                executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_range_mid_renamed"
                        + " RENAME TO test_partitioned_range_mid");
            }
        }
    }

    /**
     * A leaf reattached with a different partition bound is detected by the
     * per-leaf bound comparison.
     */
    @Test
    void treeGuardRejectsAChangedBound() throws Exception {
        long runId = populate(TABLE, uniqueLabel("guard-bound"), 2,
                ChunkingStrategy.primary_key, true);

        try {
            executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                    + " DETACH PARTITION " + SCHEMA + ".test_partitioned_range_mid");
            executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                    + " ATTACH PARTITION " + SCHEMA + ".test_partitioned_range_mid"
                    + " FOR VALUES FROM (101) TO (200)");

            assertSqlState("55000", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib
                    .Routines.assertMigrationTreeUnchanged(dsl.configuration(), runId));
        } finally {
            restoreMidPartition();
        }
    }

    /**
     * A leaf dropped and recreated with the same name and bound is detected by the
     * recorded OID.
     */
    @Test
    void treeGuardRejectsAReplacedLeaf() throws Exception {
        long runId = populate(TABLE, uniqueLabel("guard-replaced"), 2,
                ChunkingStrategy.primary_key, true);

        try {
            executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                    + " DETACH PARTITION " + SCHEMA + ".test_partitioned_range_mid");
            executeAsOwner("DROP TABLE " + SCHEMA + ".test_partitioned_range_mid");
            executeAsOwner("CREATE TABLE " + SCHEMA + ".test_partitioned_range_mid"
                    + " PARTITION OF " + SCHEMA + "." + TABLE
                    + " FOR VALUES FROM (100) TO (200)");
            // A recreated relation starts with no grants; the guard must then reach
            // the OID comparison rather than stopping at a missing privilege.
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + SCHEMA + ".test_partitioned_range_mid TO "
                    + TEST_USER + ", dml_utils_caller");

            assertSqlState("55000", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib
                    .Routines.assertMigrationTreeUnchanged(dsl.configuration(), runId));
        } finally {
            restoreMidPartition();
        }
    }

    /**
     * Recording an error never downgrades a calculated leaf, and an unknown ordinal
     * is a no-op.
     */
    @Test
    void leafErrorRecordingDoesNotDowngradeACalculatedLeaf() {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(0L, "pending")
                .execute();
        long runId = populate(TABLE, uniqueLabel("guard-record-error"), 2,
                ChunkingStrategy.primary_key, true);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .calculateMigrationRunBoundaries(dsl.configuration(), runId);

        MigrationRunLeafRecord before = leaves(runId).getFirst();
        assertEquals("calculated", before.getState());
        long boundaryCount = before.getBoundaryCount();

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.recordMigrationLeafError(
                dsl.configuration(), runId, before.getLeafOrdinal(), "XX000", "boom");
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.recordMigrationLeafError(
                dsl.configuration(), runId, 99, "XX000", "boom");

        MigrationRunLeafRecord after = leaves(runId).getFirst();
        assertEquals("calculated", after.getState());
        assertEquals(boundaryCount, after.getBoundaryCount());
        assertNull(after.getErrorSqlstate());
        assertNull(after.getErrorMessage());
    }

    /**
     * A failing leaf worker is recorded on the manifest with its SQLSTATE and
     * message, and the calculation re-raises the error without finalizing.
     */
    @Test
    void failingLeafIsRecordedOnTheManifest() throws Exception {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(0L, "pending")
                .execute();
        long runId = populate(TABLE, uniqueLabel("guard-failed-leaf"), 1,
                ChunkingStrategy.primary_key, true);

        // The run exists, but without a parent primary key the leaf calculation
        // cannot resolve the key and fails before appending a boundary.
        executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                + " DROP CONSTRAINT test_partitioned_range_pkey");
        try {
            assertSqlState("22023", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib
                    .Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId));
        } finally {
            executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE + " ADD PRIMARY KEY (id)");
        }

        MigrationRunLeafRecord failed = leaves(runId).getFirst();
        assertEquals("failed", failed.getState());
        assertEquals("22023", failed.getErrorSqlstate());
        assertNotNull(failed.getErrorMessage());
        assertNull(failed.getCalculatedAt());
        assertEquals(0L, failed.getBoundaryCount());
        assertNull(run(runId).getBoundariesCalculatedAt());
        assertTrue(boundaries(runId).isEmpty());
    }

    /**
     * The calculation stops without finalizing when the run is already cancelled.
     */
    @Test
    void calculationStopsForACancelledRun() {
        long runId = populate(TABLE, uniqueLabel("guard-cancelled"), 2,
                ChunkingStrategy.primary_key, true);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.cancelMigrationRun(
                dsl.configuration(), run(runId).getLabel());

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .calculateMigrationRunBoundaries(dsl.configuration(), runId);

        assertNotNull(run(runId).getCancelledAt());
        assertNull(run(runId).getBoundariesCalculatedAt());
        assertTrue(boundaries(runId).isEmpty());
        for (MigrationRunLeafRecord leaf : leaves(runId)) {
            assertEquals("pending", leaf.getState());
        }
    }

    /**
     * A blocks leaf rewritten on disk is detected by the per-leaf filepath
     * comparison.
     */
    @Test
    void treeGuardRejectsAChangedBlocksFilepath() throws Exception {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(0L, "pending")
                .execute();
        long runId = populate(TABLE, uniqueLabel("guard-filepath"), 1,
                ChunkingStrategy.blocks, true);

        MigrationRunLeafRecord leaf = leaves(runId).getFirst();
        assertEquals(leaf.getRelationFilepath(), liveFilepath(leaf.getLeafName()),
                "the manifest should record the leaf's current filepath");

        executeAsOwner("VACUUM FULL " + SCHEMA + ".test_partitioned_range_low");

        assertNotEquals(leaf.getRelationFilepath(), liveFilepath(leaf.getLeafName()),
                "VACUUM FULL should rewrite the heap");
        assertSqlState("55000", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertMigrationTreeUnchanged(
                dsl.configuration(), runId));
    }

    /**
     * A single-relation run has no manifest, so the tree guard is a no-op; a
     * missing run is rejected.
     */
    @Test
    void treeGuardIsANoOpForASingleRelationRunAndRejectsAMissingRun() {
        long runId = populate("test_bigint", uniqueLabel("guard-plain"), 2,
                ChunkingStrategy.primary_key, false);

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertMigrationTreeUnchanged(dsl.configuration(), runId);
        assertSqlState("P0002", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertMigrationTreeUnchanged(
                dsl.configuration(), -1L));
    }

    /**
     * The calculation entry point rejects a missing run and a single-relation run.
     */
    @Test
    void calculationRejectsAMissingOrPlainRun() {
        assertSqlState("P0002", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(
                dsl.configuration(), -1L));

        long plainRun = populate("test_bigint", uniqueLabel("calc-plain"), 2,
                ChunkingStrategy.primary_key, false);
        assertSqlState("22023", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(
                dsl.configuration(), plainRun));
    }

    /**
     * Finalizing twice keeps one terminal boundary and the same total; the second
     * calculation call is a no-op.
     */
    @Test
    void calculationIsIdempotentAfterFinalization() {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(0L, "pending")
                .execute();
        long runId = populate(TABLE, uniqueLabel("calc-idempotent"), 2,
                ChunkingStrategy.primary_key, true);

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);
        int boundaryCount = boundaries(runId).size();
        long total = run(runId).getTotalChunks();
        assertTrue(boundaryCount > 0);

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        assertEquals(boundaryCount, boundaries(runId).size());
        assertEquals(total, run(runId).getTotalChunks());
        assertNotNull(run(runId).getBoundariesCalculatedAt());
    }

    /**
     * The advisory-lock key is stable for a run and distinct between runs.
     */
    @Test
    void lockKeyIsStableAndRunSpecific() {
        long first = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.migrationRunCalculationLockKey(dsl.configuration(), 1L);
        long again = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.migrationRunCalculationLockKey(dsl.configuration(), 1L);
        long other = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.migrationRunCalculationLockKey(dsl.configuration(), 2L);

        assertEquals(first, again);
        assertNotEquals(first, other);
    }

    private long populate(String table, String label, int chunkSize, ChunkingStrategy chunkBy,
                          boolean partitionWise) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(), SCHEMA, table, label, TEMPLATE,
                        chunkSize, 1, chunkBy, "t", 0, null, partitionWise);
    }

    private String liveFilepath(String leafName) {
        return dsl.fetchOne("SELECT pg_catalog.pg_relation_filepath(?::regclass)",
                        SCHEMA + "." + leafName)
                .get(0, String.class);
    }

    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }

    private List<MigrationRunLeafRecord> leaves(long runId) {
        return dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .orderBy(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .fetch();
    }


}
