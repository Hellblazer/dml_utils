package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunSummaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.cancel_migration_run}: it cancels the active
 * unfinished run for the label, marks its pending boundaries cancelled, and
 * leaves completed, archived and absent runs alone.
 */
class CancelMigrationRunTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = payload WHERE <chunking_clause>";

    /**
     * Clears the fixture table and the migration metadata so each test starts
     * clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * Cancelling an active run sets its {@code cancelled_at} and marks every
     * unclaimed boundary cancelled, leaving the run unfinished.
     */
    @Test
    void cancelsAnActiveRunAndMarksItsPendingBoundaries() {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("cancel");
        Long runId = populate(label);
        assertNotNull(runId, "populate creates a run");

        Long cancelled = Routines.cancelMigrationRun(dsl.configuration(), label);

        assertEquals(runId, cancelled);
        MigrationRunRecord run = run(runId);
        assertNotNull(run.getCancelledAt(), "the run is cancelled");
        assertNull(run.getCompletedAt(), "a cancelled run is not completed");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "every unclaimed boundary is marked cancelled");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())),
                "nothing completed before the cancellation");
    }

    /**
     * A second cancellation of the same run is a no-op: the run is already
     * cancelled, so there is no active unfinished run to return.
     */
    @Test
    void aSecondCancellationReturnsNull() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("cancel-twice");
        assertNotNull(populate(label));
        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));

        assertNull(Routines.cancelMigrationRun(dsl.configuration(), label));
    }

    /**
     * A cancelled run is terminal: a resumed {@code run_migration_chunks} call
     * returns without processing or completing it.
     */
    @Test
    void refusesToResumeACancelledRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("cancel-resume");
        Long runId = populate(label);
        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));

        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        MigrationRunRecord run = run(runId);
        assertNotNull(run.getCancelledAt(), "the run stays cancelled");
        assertNull(run.getCompletedAt(), "the cancelled run is not completed");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())),
                "no chunk is processed after cancellation");
    }

    /**
     * A completed run is not cancellable.
     */
    @Test
    void doesNotCancelACompletedRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("cancel-completed");
        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);
        long runId = runId(label);
        assertNotNull(run(runId).getCompletedAt(), "the run completed");

        assertNull(Routines.cancelMigrationRun(dsl.configuration(), label));
        assertNull(run(runId).getCancelledAt(), "a completed run is not cancelled");
    }

    /**
     * Archiving an unfinished run cancels it first, so every archived run is
     * terminal; a later cancel is a no-op.
     */
    @Test
    void archivingAnUnfinishedRunCancelsItAndCancelIsANoOp() {
        seedBigint(1, 2);
        String label = uniqueLabel("cancel-archived");
        Long runId = populate(label);
        Routines.archiveMigrationRun(dsl.configuration(), label);

        assertNotNull(run(runId).getCancelledAt(),
                "archive cancels the unfinished run");
        assertNotNull(run(runId).getArchivedAt(), "the run is archived");
        assertNull(Routines.cancelMigrationRun(dsl.configuration(), label),
                "an archived run is not cancellable");
    }

    /**
     * A label with no active run returns NULL.
     */
    @Test
    void returnsNullWhenThereIsNoActiveRun() {
        assertNull(Routines.cancelMigrationRun(dsl.configuration(), uniqueLabel("cancel-none")));
    }

    /**
     * The run summary exposes the new {@code cancelled_at} column.
     */
    @Test
    void reportsCancellationInTheRunSummary() {
        seedBigint(1, 2);
        String label = uniqueLabel("cancel-summary");
        assertNotNull(populate(label));
        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));

        List<MigrationRunSummaryRecord> summary =
                Routines.migrationRunSummary(dsl.configuration(), label);
        assertEquals(1, summary.size());
        assertNotNull(summary.get(0).getCancelledAt(), "the summary exposes cancelled_at");
    }

    /**
     * A boundary claimed by an in-flight worker (locked by its
     * {@code SELECT ... FOR UPDATE}) is skipped instead of blocking the cancel;
     * it stays pending for the coordinator to mark after it stops the worker.
     */
    @Test
    void leavesAClaimedBoundaryForTheCoordinator() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("cancel-claimed");
        Long runId = populate(label);
        assertNotNull(runId);

        try (Connection holder = openTestConnection()) {
            holder.setAutoCommit(false);
            try (Statement claim = holder.createStatement()) {
                claim.execute("SELECT 1 FROM dml_utils_data.migration_boundary"
                        + " WHERE run_id = " + runId + " AND boundary_no = 0 FOR UPDATE");
            }

            assertEquals(runId, Routines.cancelMigrationRun(dsl.configuration(), label),
                    "the cancel does not wait for the claimed boundary");

            assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                            MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                    .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                                    .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull())),
                    "the claimed boundary is left pending");
            assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                            MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                    .and(MIGRATION_BOUNDARY.BOUNDARY_NO.ne(0L))
                                    .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                    "every unclaimed boundary is marked");
        }

        // The coordinator's post-cancel pass picks up the boundary the worker held.
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .markMigrationRunCancelled(dsl.configuration(), runId);
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the coordinator marks the boundary the cancel skipped");
    }

    /**
     * A leaf transaction that claimed the run before the cancel commits its
     * boundaries first: the cancel waits on the run row, then marks them. A
     * claim that starts later sees {@code cancelled_at} and writes nothing.
     */
    @Test
    void waitsForAnInFlightLeafBeforeMarkingItsBoundaries() throws Exception {
        seedBigint(1, 2, 3, 4, 5);
        String label = uniqueLabel("cancel-in-flight");
        Long runId = populate(label);
        assertNotNull(runId);

        try (Connection holder = openTestConnection()) {
            holder.setAutoCommit(false);
            try (Statement claim = holder.createStatement()) {
                // What claim_next_migration_leaf does: lock the run row, then write
                // the leaf's boundaries in the same transaction.
                claim.execute("SELECT 1 FROM dml_utils_data.migration_run"
                        + " WHERE run_id = " + runId + " FOR UPDATE");
                claim.execute("INSERT INTO dml_utils_data.migration_boundary"
                        + " (run_id, boundary_no, boundary_id)"
                        + " VALUES (" + runId + ", 999,"
                        + " ROW(ARRAY[999::bigint], NULL, NULL, NULL)::dml_utils_data.migration_key)");
            }

            try (Connection caller = openTestConnection();
                 Statement cancel = caller.createStatement()) {
                cancel.execute("SET lock_timeout = '1s'");
                SQLException error = assertThrows(SQLException.class, () -> cancel.execute(
                        "SELECT dml_utils.cancel_migration_run('" + label + "')"));
                assertEquals("55P03", error.getSQLState(),
                        () -> "unexpected error: " + error.getMessage());
            }

            holder.commit();
        }

        assertEquals(runId, Routines.cancelMigrationRun(dsl.configuration(), label));
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the boundary committed by the in-flight leaf is marked");
    }

    /**
     * Creates the run and its boundaries without processing any chunk.
     *
     * @param label the run label
     * @return the new run id
     */
    private Long populate(String label) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_BIGINT), name(TEST_BIGINT), label,
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
    }

    /**
     * Reads the run row.
     *
     * @param runId the run id
     * @return the run record
     */
    private MigrationRunRecord run(Long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
