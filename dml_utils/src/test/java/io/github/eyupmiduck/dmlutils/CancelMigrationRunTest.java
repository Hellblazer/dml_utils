package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunSummaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

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
                ChunkingStrategy.primary_key, 1, 0, null);

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
                ChunkingStrategy.primary_key, 1, 0, null);
        Long runId = dsl.fetchOne(
                        "SELECT run_id FROM dml_utils_data.migration_run WHERE label = ?", label)
                .get(0, Long.class);
        assertNotNull(run(runId).getCompletedAt(), "the run completed");

        assertNull(Routines.cancelMigrationRun(dsl.configuration(), label));
        assertNull(run(runId).getCancelledAt(), "a completed run is not cancelled");
    }

    /**
     * An archived run is not cancellable.
     */
    @Test
    void doesNotCancelAnArchivedRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("cancel-archived");
        Long runId = populate(label);
        Routines.archiveMigrationRun(dsl.configuration(), label);

        assertNull(Routines.cancelMigrationRun(dsl.configuration(), label));
        assertNull(run(runId).getCancelledAt(), "an archived run is not cancelled");
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
     * Creates the run and its boundaries without processing any chunk.
     *
     * @param label the run label
     * @return the new run id
     */
    private Long populate(String label) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_BIGINT), name(TEST_BIGINT), label,
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null);
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
