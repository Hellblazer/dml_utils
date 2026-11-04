package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies partition-wise execution through {@code dml_utils.run_migration_chunks}:
 * a new run calculates and finalizes every leaf before processing, a partially
 * calculated run resumes calculation instead of processing, the live tree is
 * compared with the manifest, calculation is serialized, and cancellation stops
 * the leaf workers.
 */
class PartitionWiseRunnerTest extends PartitionTestBase {

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
     * A new partition-wise run creates the manifest, calculates and finalizes every
     * leaf, then processes each chunk against its leaf.
     */
    @Test
    void runsAPartitionWiseMigrationAcrossEveryLeaf() {
        insertRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("partition-wise-run");

        run(TABLE, label, 2, true);

        MigrationRunRecord run = run(label);
        assertTrue(run.getPartitionWise());
        assertNotNull(run.getBoundariesCalculatedAt(),
                "boundary calculation should be finalized before processing");
        assertNotNull(run.getCompletedAt(), "the run should complete");
        assertEquals(5L, run.getTotalChunks());
        assertEquals(5L, run.getTotalChunksCompleted());

        List<MigrationRunLeafRecord> leaves = leaves(run.getRunId());
        assertEquals(3, leaves.size());
        for (MigrationRunLeafRecord leaf : leaves) {
            assertEquals("calculated", leaf.getState());
            assertNotNull(leaf.getCalculatedAt());
        }
        assertEquals(2L, leaves.get(0).getBoundaryCount());
        assertEquals(1L, leaves.get(1).getBoundaryCount());
        assertEquals(2L, leaves.get(2).getBoundaryCount());

        List<MigrationBoundaryRecord> boundaries = boundaries(run.getRunId());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L),
                boundaries.stream().map(MigrationBoundaryRecord::getBoundaryNo).toList());
        for (MigrationBoundaryRecord boundary : boundaries) {
            assertNotNull(boundary.getLeafOrdinal(), "every partition-wise boundary names its leaf");
            if (boundary.getBoundaryNo() < run.getTotalChunks()) {
                assertNotNull(boundary.getCompletedAt(), "every chunk boundary is completed");
            }
        }
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A leaf whose tail was deleted after calculation is processed against its
     * stored high-water snapshot, so an empty/regressed leaf does not abort the
     * run.
     */
    @Test
    void terminalSnapshotSurvivesTailDeletion() {
        insertRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("partition-wise-delete-tail");
        long runId = populate(TABLE, label, 2, ChunkingStrategy.primary_key, null, true);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .calculateMigrationRunBoundaries(dsl.configuration(), runId);

        // The low leaf's rows disappear between calculation and processing.
        dsl.deleteFrom(TEST_PARTITIONED_RANGE)
                .where(TEST_PARTITIONED_RANGE.ID.lt(100L))
                .execute();

        run(TABLE, label, 2, true);

        assertNotNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
        assertEquals(5, dsl.fetchCount(TEST_PARTITIONED_RANGE));
    }

    /**
     * A cancellation that lands while a leaf is being calculated stops the run
     * before processing and cancels the boundaries the leaf just committed.
     */
    @Test
    void cancellationDuringCalculationStopsBeforeProcessing() throws Exception {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-cancel-during");
        long runId = populate(TABLE, label, 2, ChunkingStrategy.primary_key, null, true);

        // Fault injection: cancel the run as soon as the first leaf is marked
        // calculated, which is the window between calculation and processing.
        executeAsOwner("CREATE OR REPLACE FUNCTION " + SCHEMA + ".cancel_run_after_leaf()"
                + " RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " UPDATE dml_utils_data.migration_run"
                + " SET cancelled_at = pg_catalog.clock_timestamp()"
                + " WHERE run_id = NEW.run_id AND cancelled_at IS NULL;"
                + " RETURN NEW; END; $$");
        executeAsOwner("DROP TRIGGER IF EXISTS cancel_run_after_leaf"
                + " ON dml_utils_data.migration_run_leaf");
        executeAsOwner("CREATE TRIGGER cancel_run_after_leaf"
                + " AFTER UPDATE ON dml_utils_data.migration_run_leaf"
                + " FOR EACH ROW WHEN (NEW.state = 'calculated')"
                + " EXECUTE FUNCTION " + SCHEMA + ".cancel_run_after_leaf()");
        try {
            run(TABLE, label, 2, true);
        } finally {
            executeAsOwner("DROP TRIGGER IF EXISTS cancel_run_after_leaf"
                    + " ON dml_utils_data.migration_run_leaf");
            executeAsOwner("DROP FUNCTION IF EXISTS " + SCHEMA + ".cancel_run_after_leaf()");
        }

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCancelledAt());
        assertNull(run.getBoundariesCalculatedAt(),
                "a cancelled calculation must not finalize");
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.PAYLOAD.eq("done")),
                "no chunk should run after cancellation");
        for (MigrationBoundaryRecord boundary : boundaries(runId)) {
            assertNull(boundary.getCompletedAt());
            assertNotNull(boundary.getCancelledAt(),
                    "the leaf's committed starts should be cancelled");
        }
    }

    /**
     * A partition-wise blocks run records one filepath per leaf, keeps the run-level
     * filepath NULL, and processes each leaf's ctid range.
     */
    @Test
    void blocksRunProcessesEveryLeaf() {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-blocks");

        run(TABLE, label, 1, ChunkingStrategy.blocks, null, true);

        MigrationRunRecord run = run(label);
        assertTrue(run.getPartitionWise());
        assertNull(run.getDrivingTableRelationFilepath(),
                "a partition-wise blocks run records filepaths per leaf, not per run");
        assertNotNull(run.getBoundariesCalculatedAt());
        assertEquals(3L, run.getTotalChunks());
        for (MigrationRunLeafRecord leaf : leaves(run.getRunId())) {
            assertNotNull(leaf.getRelationFilepath(),
                    "every leaf records its own heap filepath");
        }
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A resumed run uses its stored partition_wise flag even when the caller passes
     * false, and still finishes the leaf calculation before processing.
     */
    @Test
    void storedPartitionWiseFlagWinsOnResume() {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-flag");
        long runId = populate(TABLE, label, 2, ChunkingStrategy.primary_key, null, true);

        run(TABLE, label, 2, false);

        assertTrue(run(label).getPartitionWise(), "the stored flag wins");
        assertNotNull(run(label).getBoundariesCalculatedAt());
        assertEquals(3L, run(label).getTotalChunks());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
        assertEquals(3, leaves(runId).size());
    }

    /**
     * Creating a partition-wise run fails before the run row commits when the
     * caller cannot read a leaf.
     */
    @Test
    void rejectsALeafTheCallerCannotRead() throws Exception {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-privilege");

        executeAsOwner("REVOKE SELECT ON " + SCHEMA + ".test_partitioned_range_mid"
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> populate(TABLE, label, 2,
                    ChunkingStrategy.primary_key, null, true));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + SCHEMA + ".test_partitioned_range_mid TO "
                    + TEST_USER + ", dml_utils_caller");
        }

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "a rejected manifest must not leave a run row");
    }

    /**
     * A run whose leaves are only partially calculated resumes at the first pending
     * leaf instead of processing, and does not duplicate the committed leaf's
     * boundaries.
     */
    @Test
    void resumesPartialCalculationAtTheFirstPendingLeaf() {
        insertRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("partition-wise-resume");

        long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TABLE, label, TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true);

        assertEquals(1, io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationLeaf(dsl.configuration(), runId));
        long firstLeafBoundaries = leaves(runId).getFirst().getBoundaryCount();
        assertEquals(2L, firstLeafBoundaries);
        assertNull(run(label).getBoundariesCalculatedAt());

        run(TABLE, label, 2, true);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getBoundariesCalculatedAt());
        assertNotNull(run.getCompletedAt());
        assertEquals(5L, run.getTotalChunks());
        assertEquals(firstLeafBoundaries, leaves(runId).getFirst().getBoundaryCount(),
                "the already calculated leaf must not be recalculated");
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L),
                boundaries(runId).stream()
                        .map(MigrationBoundaryRecord::getBoundaryNo).toList());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A partition tree changed before processing fails closed with {@code 55000}
     * and does not process any chunk.
     */
    @Test
    void changedTreeBeforeProcessingFailsClosed() throws Exception {
        insertRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("partition-wise-changed-before");

        long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TABLE, label, TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                + " DETACH PARTITION " + SCHEMA + ".test_partitioned_range_mid");
        try {
            assertSqlState("55000", () -> run(TABLE, label, 2, true));
        } finally {
            restoreMidPartition();
        }

        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.PAYLOAD.eq("done")),
                "no chunk should run against a changed tree");
    }

    /**
     * A partition tree changed while calculation is still pending fails closed
     * before any leaf is calculated.
     */
    @Test
    void changedTreeDuringCalculationFailsClosed() throws Exception {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-changed-during");

        long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TABLE, label, TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true);

        executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                + " DETACH PARTITION " + SCHEMA + ".test_partitioned_range_mid");
        try {
            assertSqlState("55000", () -> run(TABLE, label, 2, true));
        } finally {
            restoreMidPartition();
        }

        assertNull(run(label).getBoundariesCalculatedAt());
        assertTrue(boundaries(runId).isEmpty());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.eq("done")));
    }

    /**
     * Cancelling a run before its leaves are calculated stops the calculation and
     * processing; the pending leaves are left for the operator to archive.
     */
    @Test
    void cancellationStopsLeafCalculation() {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-cancel");

        long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TABLE, label, TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true);
        Routines.cancelMigrationRun(dsl.configuration(), label);

        run(TABLE, label, 2, true);

        MigrationRunRecord run = run(label);
        assertNotNull(run.getCancelledAt());
        assertNull(run.getBoundariesCalculatedAt(), "a cancelled calculation is not finalized");
        assertTrue(boundaries(runId).isEmpty());
        for (MigrationRunLeafRecord leaf : leaves(runId)) {
            assertEquals("pending", leaf.getState());
        }
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.eq("done")));
    }

    /**
     * A second calculation coordinator cannot take the run's advisory lock and
     * fails closed instead of appending duplicate boundaries.
     */
    @Test
    void concurrentCalculationFailsClosed() throws Exception {
        insertRows(0L, 100L, 200L);
        String label = uniqueLabel("partition-wise-concurrent");
        long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TABLE, label, TEMPLATE, 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true);

        try (Connection other = openTestConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock("
                        + "dml_utils_lib.migration_run_calculation_lock_key(" + runId + "))");

                assertSqlState("55000",
                        () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId));
            } finally {
                other.rollback();
            }
        }

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);
        assertNotNull(run(label).getBoundariesCalculatedAt());
        assertEquals(3L, run(label).getTotalChunks());
    }

    /**
     * A partition-wise run on a plain table is rejected before a run row exists.
     */
    @Test
    void rejectsANonPartitionedDrivingTable() {
        String label = uniqueLabel("partition-wise-plain");

        assertSqlState("42809", () -> run("test_bigint", label, 1, true));

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "no run row should be created by a rejected partition-wise call");
    }

    private void run(String table, String label, int chunkSize, boolean partitionWise) {
        run(table, label, chunkSize, ChunkingStrategy.primary_key, null, partitionWise);
    }

    private void run(String table, String label, int chunkSize, ChunkingStrategy chunkBy,
                     String boundaryKeyIndex, boolean partitionWise) {
        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, SCHEMA, table, label,
                chunkSize, 1, "t", chunkBy, 1, 0, boundaryKeyIndex, partitionWise);
    }

    private long populate(String table, String label, int chunkSize, ChunkingStrategy chunkBy,
                          String boundaryKeyIndex, boolean partitionWise) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(), SCHEMA, table, label, TEMPLATE,
                        chunkSize, 1, chunkBy, "t", 0, boundaryKeyIndex, partitionWise);
    }

    private void insertRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_PARTITIONED_RANGE,
                            TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(id, "pending")
                    .execute();
        }
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


}
