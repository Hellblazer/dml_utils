package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationBoundariesRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationErrorsRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunSummaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the migration-maintenance functions: deleting archived runs (all or
 * by label, cascading to boundaries and errors), the per-label run summary, and
 * the per-run error listing.
 */
class MigrationMaintenanceTest extends PostgresTestBase {

    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * Deleting archived runs removes only the archived ones and cascades to
     * their boundaries and errors.
     */
    @Test
    void deleteAllArchivedRunsDeletesThemAndCascades() {
        String archivedLabel = "maint-all-archived";
        long archivedRun = populateRun(archivedLabel, 2, 1, 2, 3, 4);
        recordError(archivedRun, 0L, "22012", "division by zero");
        assertEquals(archivedRun, Routines.archiveMigrationRun(dsl.configuration(), archivedLabel));
        long activeRun = populateRun("maint-all-active", 2, 1, 2);

        long deleted = Routines.deleteArchivedMigrationRuns(dsl.configuration(), null, 1000);

        assertEquals(1, deleted, "only the archived run should be deleted");
        assertNull(runRow(archivedLabel), "the archived run should be gone");
        assertNotNull(runRow("maint-all-active"), "the active run should remain");
        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(archivedRun)),
                "the archived run's errors should be cascaded");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(archivedRun)),
                "the archived run's boundaries should be cascaded");
        assertTrue(dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(activeRun)) > 0,
                "the active run's boundaries should remain");
    }

    /**
     * A non-terminal archived run is left in place, so a delete cannot remove a
     * run that might still have an in-flight worker. (archive_migration_run
     * normally prevents this state by cancelling first.)
     */
    @Test
    void deleteSkipsANonTerminalArchivedRun() {
        long runId = populateRun("maint-nonterminal", 2, 1, 2);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ARCHIVED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        assertEquals(0, Routines.deleteArchivedMigrationRuns(
                        dsl.configuration(), "maint-nonterminal", 1000),
                "a non-terminal archived run must not be deleted");
        assertNotNull(runRow("maint-nonterminal"), "the run remains");

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        // The direct cancellation above skipped the boundary pass that
        // cancel_migration_run performs; mark them as the coordinator would, so no
        // boundary looks like an in-flight one.
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNull())
                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())
                .execute();
        assertEquals(1, Routines.deleteArchivedMigrationRuns(
                        dsl.configuration(), "maint-nonterminal", 1000),
                "once terminal, the archived run is deleted");
    }

    /**
     * A completed-then-archived run is deleted like a cancelled one; the terminal
     * filter is not restricted to cancellations.
     */
    @Test
    void deletesACompletedThenArchivedRun() {
        String label = "maint-completed";
        long runId = populateRun(label, 2, 1, 2);
        // Complete the single chunk (boundaries 0 and the terminal 1) and the run.
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, OffsetDateTime.now(), 0L);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationRun(
                dsl.configuration(), runId);
        assertNotNull(dsl.select(MIGRATION_RUN.COMPLETED_AT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT), "the run completed");
        assertNotNull(Routines.archiveMigrationRun(dsl.configuration(), label));

        assertEquals(1, Routines.deleteArchivedMigrationRuns(
                dsl.configuration(), label, 1000));
        assertNull(runRow(label), "the completed archived run is deleted");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)),
                "its boundaries are cascaded");
    }

    /**
     * The delete is bounded by i_limit, so a caller can clean up a large backlog in
     * batches; a loop until it returns 0 removes everything.
     */
    @Test
    void deletesAtMostTheBatchLimitPerCall() {
        populateRun("maint-batch-a", 2, 1);
        Routines.archiveMigrationRun(dsl.configuration(), "maint-batch-a");
        populateRun("maint-batch-b", 2, 2);
        Routines.archiveMigrationRun(dsl.configuration(), "maint-batch-b");

        assertEquals(1, Routines.deleteArchivedMigrationRuns(dsl.configuration(), null, 1),
                "the first call deletes one run");
        assertEquals(1, runCount("maint-batch-a") + runCount("maint-batch-b"),
                "one archived run remains");

        assertEquals(1, Routines.deleteArchivedMigrationRuns(dsl.configuration(), null, 1),
                "the second call deletes the other run");
        assertEquals(0, runCount("maint-batch-a") + runCount("maint-batch-b"));
        assertEquals(0, Routines.deleteArchivedMigrationRuns(dsl.configuration(), null, 1),
                "a drained backlog returns 0");
    }

    /**
     * A terminal run with a pending boundary is skipped: cancel leaves exactly the
     * boundaries an in-flight worker had claimed pending, so deleting such a run
     * could cascade into rows that worker still locks.
     */
    @Test
    void skipsARunWithAnInFlightBoundary() {
        String label = "maint-in-flight";
        long runId = populateRun(label, 2, 1, 2);
        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));
        assertNotNull(Routines.archiveMigrationRun(dsl.configuration(), label));

        // Simulate the boundary a worker had claimed when the run was cancelled:
        // cancel marks the unclaimed ones, leaving this one pending.
        dsl.execute("""
                INSERT INTO dml_utils_data.migration_boundary (run_id, boundary_no, boundary_id)
                VALUES (?, 99, ROW(ARRAY[99::bigint], NULL, NULL, NULL)::dml_utils_data.migration_key)
                """, runId);

        assertEquals(0, Routines.deleteArchivedMigrationRuns(dsl.configuration(), label, 1000),
                "a pending boundary defers the delete");
        assertNotNull(runRow(label), "the run is kept");

        // The documented recovery for a stranded run: the metadata helper marks the
        // pending boundary (and is idempotent for the already-cancelled run).
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .markMigrationRunCancelled(dsl.configuration(), runId);

        assertEquals(1, Routines.deleteArchivedMigrationRuns(dsl.configuration(), label, 1000),
                "the run is deleted once no boundary is pending");
    }

    /**
     * Deleting archived runs for a label leaves other labels' archived runs.
     */
    @Test
    void deleteArchivedRunsForLabelDeletesOnlyThatLabel() {
        // Two archived runs under label-a, plus an active and an archived run under
        // label-b, with child rows on the runs that must survive.
        long firstA = populateRun("maint-label-a", 2, 1, 2);
        recordError(firstA, 0L, "22012", "boom");
        Routines.archiveMigrationRun(dsl.configuration(), "maint-label-a");
        populateRun("maint-label-a", 2, 3, 4);
        Routines.archiveMigrationRun(dsl.configuration(), "maint-label-a");

        long archivedB = populateRun("maint-label-b", 2, 5, 6);
        Routines.archiveMigrationRun(dsl.configuration(), "maint-label-b");
        long activeB = populateRun("maint-label-b", 2, 7, 8);
        recordError(activeB, 0L, "22012", "keep");

        long deleted = Routines.deleteArchivedMigrationRuns(dsl.configuration(), "maint-label-a", 1000);

        assertEquals(2, deleted, "both archived runs for label-a should be deleted");
        assertEquals(0, runCount("maint-label-a"), "label-a's archived runs should be gone");
        assertEquals(2, runCount("maint-label-b"), "label-b's runs should remain");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(activeB)),
                "another label's error should survive");
        assertTrue(dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(archivedB)) > 0,
                "another label's archived boundaries should survive");
    }

    /**
     * The summary reports one high-level row per run with the boundary and error
     * counts.
     */
    @Test
    void migrationRunSummaryReportsHighLevelCounts() {
        String label = "maint-summary";
        long runId = populateRun(label, 2, 1, 2, 3, 4);
        recordError(runId, 0L, "22012", "boom one");
        recordError(runId, 1L, "23505", "boom two");

        List<MigrationRunSummaryRecord> summaries = Routines.migrationRunSummary(
                dsl.configuration(), label);
        assertEquals(1, summaries.size(), "one row per run for the label");
        MigrationRunSummaryRecord summary = summaries.get(0);

        assertNotNull(summary);
        assertEquals(runId, summary.getRunId().longValue());
        assertEquals(2, summary.getChunkSize().intValue());
        assertEquals(1, summary.getThreads().intValue());
        assertEquals(0, summary.getSleepMs().intValue(),
                "the default run has no per-chunk delay");
        assertEquals(TEST_BIGINT.getSchema().getName(), summary.getDrivingTableSchemaName());
        assertEquals(TEST_BIGINT.getName(), summary.getDrivingTableName());
        assertEquals(3L, summary.getBoundaryCount().longValue(), "chunk starts plus terminal");
        assertEquals(0L, summary.getCompletedBoundaryCount().longValue());
        assertEquals(2L, summary.getErrorCount().longValue());
        assertNotNull(summary.getStartedAt(), "the run start is recorded when the run is created");
        assertNotNull(summary.getBoundariesCalculatedAt(),
                "the range-calculation completion is recorded");
        assertEquals("primary_key", summary.getChunkBy(), "a default run uses primary_key");
        assertNull(summary.getDrivingTableRelationFilepath(),
                "a primary-key run records no relation filepath");
        assertNull(summary.getCompletedAt(), "a fresh run has not finished");
        assertNull(summary.getArchivedAt());
    }

    /**
     * The summary reflects a run whose boundaries are partly completed and which
     * is then completed and archived: the completed-boundary count, completed_at
     * and archived_at all come through.
     */
    @Test
    void migrationRunSummaryReportsCompletedAndArchivedState() {
        String label = "maint-summary-done";
        long runId = populateRun(label, 2, 1, 2, 3, 4);
        // Complete two of the three boundaries and finish the run.
        OffsetDateTime started = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, started)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .set(MIGRATION_BOUNDARY.ROWS_AFFECTED, 0L)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.in(0L, 1L))
                .execute();
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, started)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        Routines.archiveMigrationRun(dsl.configuration(), label);

        List<MigrationRunSummaryRecord> summaries = Routines.migrationRunSummary(
                dsl.configuration(), label);
        assertEquals(1, summaries.size(), "the label has one run");
        MigrationRunSummaryRecord summary = summaries.get(0);

        assertEquals(3L, summary.getBoundaryCount().longValue());
        assertEquals(2L, summary.getCompletedBoundaryCount().longValue(),
                "two of the three boundaries are completed");
        assertNotNull(summary.getStartedAt(), "the run start comes through");
        assertTrue(started.isEqual(summary.getStartedAt()), "the run start value comes through");
        assertNotNull(summary.getBoundariesCalculatedAt(),
                "the range-calculation completion comes through");
        assertNotNull(summary.getCompletedAt(), "the run is completed");
        assertNotNull(summary.getArchivedAt(), "the run is archived");
    }

    /**
     * The summary returns one row per run for the label, including archived runs.
     */
    @Test
    void migrationRunSummaryListsEveryRunForALabel() {
        long first = populateRun("maint-summary-multi", 2, 1, 2);
        Routines.archiveMigrationRun(dsl.configuration(), "maint-summary-multi");
        long second = populateRun("maint-summary-multi", 2, 3, 4);

        List<MigrationRunSummaryRecord> summaries = Routines.migrationRunSummary(
                dsl.configuration(), "maint-summary-multi");

        assertEquals(2, summaries.size(), "one row per run for the label");
        assertEquals(List.of(first, second),
                summaries.stream().map(s -> s.getRunId().longValue()).toList(),
                "the rows are ordered by run id");
    }

    /**
     * The error listing returns the run's recorded errors ordered by error id
     * (insertion order), independent of the boundary number.
     */
    @Test
    void migrationErrorsReturnsTheRecordedErrors() {
        long runId = populateRun("maint-errors", 2, 1, 2);
        // Insert out of boundary order: the listing order is the error-id order.
        recordError(runId, 1L, "23505", "duplicate key");
        recordError(runId, 0L, "22012", "division by zero");

        List<MigrationErrorsRecord> errors = Routines.migrationErrors(dsl.configuration(), runId);

        assertEquals(List.of("1|23505|duplicate key", "0|22012|division by zero"),
                errors.stream()
                        .map(e -> e.getBoundaryNo() + "|" + e.getSqlstate() + "|" + e.getMessage())
                        .toList());
    }

    /**
     * The boundary listing returns the run's boundaries in order, with the
     * packed key, row count, duration and the start/completion timestamps of
     * each. A pending chunk has null {@code started_at}, {@code completed_at},
     * {@code rows_affected} and {@code duration_ms}; a non-partitioned boundary
     * has no leaf.
     */
    @Test
    void migrationBoundariesReturnsTheRunsBoundaries() {
        String label = "maint-boundaries";
        long runId = populateRun(label, 2, 1, 2, 3);
        OffsetDateTime started = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, started)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .set(MIGRATION_BOUNDARY.ROWS_AFFECTED, 0L)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .execute();

        List<MigrationBoundariesRecord> boundaries = Routines.migrationBoundaries(
                dsl.configuration(), runId);

        assertEquals(3, boundaries.size(), "two chunk starts plus the terminal boundary");
        assertEquals(List.of(0L, 1L, 2L),
                boundaries.stream().map(b -> b.getBoundaryNo().longValue()).toList());
        assertEquals(1L, boundaries.get(0).getBoundaryId().getBigintValues()[0].longValue(),
                "the first boundary packs the first id");
        assertNotNull(boundaries.get(0).getStartedAt(), "the completed chunk has a start");
        assertTrue(started.isEqual(boundaries.get(0).getStartedAt()),
                "the boundary start value comes through");
        assertNotNull(boundaries.get(0).getCompletedAt(), "the completed chunk has a timestamp");
        assertEquals(0L, boundaries.get(0).getRowsAffected().longValue(),
                "the completed chunk records its row count");
        assertNotNull(boundaries.get(0).getDurationMs(),
                "a completed chunk records its duration");
        assertNull(boundaries.get(0).getLeafOrdinal(),
                "a non-partitioned boundary has no leaf");
        assertNull(boundaries.get(0).getCancelledAt(),
                "a completed boundary is not cancelled");
        assertNull(boundaries.get(1).getStartedAt(), "a pending chunk has not started");
        assertNull(boundaries.get(1).getCompletedAt(), "a pending chunk has no timestamp");
        assertNull(boundaries.get(1).getRowsAffected(),
                "a pending chunk has no row count");
        assertNull(boundaries.get(1).getDurationMs(),
                "a pending chunk has no duration");
        assertNull(boundaries.get(2).getCompletedAt(), "the terminal boundary is pending");
    }

    /**
     * Resets the driving table, inserts the ids and creates a run for the label.
     */
    private long populateRun(String label, int chunkSize, long... ids) {
        dsl.truncate(TEST_BIGINT).execute();
        for (long id : ids) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                label, "SELECT 1", chunkSize, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
    }

    private void recordError(long runId, long boundaryNo, String sqlstate, String message) {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.recordMigrationError(dsl.configuration(), runId, boundaryNo, sqlstate, message);
    }

    /**
     * The latest run id for the label, or null when the label has no run. The
     * query is limited so an archived run plus a newer active run with the same
     * label does not make fetchOne throw.
     *
     * @param label the run label
     * @return the latest run id, or null
     */
    private Long runRow(String label) {
        return dsl.select(MIGRATION_RUN.RUN_ID)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .orderBy(MIGRATION_RUN.RUN_ID.desc())
                .limit(1)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    private int runCount(String label) {
        return dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label));
    }
}
