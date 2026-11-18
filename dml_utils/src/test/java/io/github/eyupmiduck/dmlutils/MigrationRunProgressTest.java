package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationBoundariesRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunProgressRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.migration_run_progress} across every run state and
 * the extended {@code dml_utils.migration_boundaries}: the derived status,
 * percent complete, throughput rates, ETA and the division-by-zero guards, plus
 * the boundary leaf, row-count and duration columns.
 */
class MigrationRunProgressTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_partitioned_range";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Resets the migration metadata and both driving fixtures between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsBigintAndRange();
    }

    /**
     * A partition-wise run whose leaves are not calculated reports
     * {@code calculating} with no rates or ETA.
     */
    @Test
    void reportsACalculatingRun() {
        insertRangeRows(0L, 1L, 100L);
        String label = uniqueLabel("progress-calculating");
        long runId = populatePartitionWise(label, 2);

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("calculating", progress.getStatus());
        assertTrue(progress.getPartitionWise());
        assertEquals("primary_key", progress.getChunkBy());
        assertEquals(0L, progress.getTotalChunks());
        assertEquals(0L, progress.getCompletedChunks());
        assertEquals(0L, progress.getRemainingChunks());
        assertEquals(0.0, progress.getPercentComplete(), 0.001,
                "an unfinished, uncalculated run has no progress");
        assertNotNull(progress.getStartedAt());
        assertNull(progress.getBoundariesCalculatedAt());
        assertNull(progress.getChunksPerSecond(),
                "a calculating run has no chunk rate");
        assertNull(progress.getRowsPerSecond(),
                "a calculating run has no row rate");
        assertNull(progress.getEstimatedRemainingMs(),
                "a calculating run has no ETA");
        assertNull(progress.getEstimatedCompletionAt());
        assertNull(progress.getPausedAt());
        assertEquals(3, progress.getMaxRetries());
        assertEquals(1000, progress.getRetryBackoffMs());
        assertNull(progress.getCompletedAt());
        assertNull(progress.getCancelledAt());
        assertNull(progress.getArchivedAt());
    }

    /**
     * An in-flight run reports its completed fraction, rates and a forward ETA.
     */
    @Test
    void reportsAnActiveRun() throws Exception {
        String label = uniqueLabel("progress-active");
        long runId = populateSingle(label, 2, 1L, 2L, 3L, 4L, 5L);
        // The recorded start is immutable; let real time pass instead.
        Thread.sleep(1200);
        completeBoundary(runId, 0L, 10L);

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("active", progress.getStatus());
        assertFalse(progress.getPartitionWise());
        assertEquals(3L, progress.getTotalChunks());
        assertEquals(1L, progress.getCompletedChunks());
        assertEquals(2L, progress.getRemainingChunks());
        assertEquals(33.33, progress.getPercentComplete(), 0.01);
        assertEquals(10L, progress.getTotalRowsAffected());
        // Compare against the database's own elapsed time, read independently of
        // the view, rather than deriving the expectation from the view's output.
        long measuredMs = databaseElapsedMs(runId);
        // databaseElapsedMs is sampled after the view, so it can only be larger
        // than the reported elapsed; allow the round-trip gap in one direction.
        assertTrue(progress.getElapsedMs() >= 1000,
                "the elapsed time is measured from started_at");
        assertTrue(progress.getElapsedMs() <= measuredMs,
                "the reported elapsed time does not exceed the sampled clock");
        double measuredRate = 1.0 / (progress.getElapsedMs() / 1000.0);
        assertEquals(measuredRate, progress.getChunksPerSecond(),
                measuredRate * 0.1, "one chunk per measured elapsed second");
        assertEquals(10.0 * measuredRate, progress.getRowsPerSecond(),
                10.0 * measuredRate * 0.1);
        assertNotNull(progress.getEstimatedRemainingMs(),
                "an active run with a rate has an ETA");
        assertEquals(2 * 1000.0 / measuredRate,
                progress.getEstimatedRemainingMs().doubleValue(), 500.0,
                "two remaining chunks at the measured rate");
        assertNotNull(progress.getEstimatedCompletionAt());
    }

    /**
     * A calculated run that has not completed a chunk yet reports no rate or ETA
     * instead of dividing by zero completed chunks.
     */
    @Test
    void reportsZeroCompletedChunksWithoutRates() {
        String label = uniqueLabel("progress-no-completions");
        long runId = populateSingle(label, 2, 1L, 2L, 3L, 4L, 5L);

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("active", progress.getStatus());
        assertEquals(3L, progress.getTotalChunks());
        assertEquals(0L, progress.getCompletedChunks());
        assertEquals(3L, progress.getRemainingChunks());
        assertEquals(0.0, progress.getPercentComplete(), 0.001);
        assertNull(progress.getChunksPerSecond());
        assertNull(progress.getRowsPerSecond());
        assertNull(progress.getEstimatedRemainingMs());
        assertNull(progress.getEstimatedCompletionAt());
    }

    /**
     * A zero-length run (start and finish at the same instant) reports no rates
     * instead of dividing by zero elapsed time.
     */
    @Test
    void avoidsDivisionByZeroWhenElapsedIsZero() {
        String label = uniqueLabel("progress-zero-elapsed");
        long runId = populateSingle(label, 2, 1L, 2L);
        completeBoundary(runId, 0L, 5L);
        // Finish at the same instant the run started. This writes the completion
        // directly instead of going through complete_migration_run, because a
        // completion timestamp is immutable once set and the point here is the
        // view's zero-elapsed branch, not the completion protocol. The milestone
        // ordering still allows it because the boundaries were calculated after the
        // start.
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, migrationRun(runId).getStartedAt())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertNotNull(migrationRun(runId).getCompletedAt());

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("completed", progress.getStatus());
        assertEquals(0L, progress.getElapsedMs());
        assertEquals(100.0, progress.getPercentComplete(), 0.001);
        assertNull(progress.getChunksPerSecond(),
                "a zero elapsed time has no chunk rate");
        assertNull(progress.getRowsPerSecond(),
                "a zero elapsed time has no row rate");
        assertEquals(0L, progress.getEstimatedRemainingMs());
    }

    /**
     * A paused run keeps its rates but reports no forward ETA, and clears the
     * estimate instead of pretending it is still progressing.
     */
    @Test
    void reportsAPausedRun() throws Exception {
        String label = uniqueLabel("progress-paused");
        long runId = populateSingle(label, 2, 1L, 2L, 3L, 4L);
        Thread.sleep(1200);
        completeBoundary(runId, 0L, 5L);
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("paused", progress.getStatus());
        assertNotNull(progress.getPausedAt());
        assertEquals(2L, progress.getTotalChunks());
        assertEquals(1L, progress.getCompletedChunks());
        assertEquals(1L, progress.getRemainingChunks());
        assertEquals(50.0, progress.getPercentComplete(), 0.01);
        assertNotNull(progress.getChunksPerSecond(),
                "the completed chunks still have a historical rate");
        assertNull(progress.getEstimatedRemainingMs(),
                "a paused run has no forward ETA");
        assertNull(progress.getEstimatedCompletionAt());
    }

    /**
     * A completed run reports 100%, the final throughput and its completion
     * timestamp as the estimated finish.
     */
    @Test
    void reportsACompletedRun() throws Exception {
        String label = uniqueLabel("progress-completed");
        long runId = populateSingle(label, 2, 1L, 2L, 3L, 4L, 5L);
        // The recorded start is immutable; give the run a measurable duration.
        Thread.sleep(50);
        completeBoundary(runId, 0L, 10L);
        completeBoundary(runId, 1L, 10L);
        completeBoundary(runId, 2L, 10L);
        completeRun(runId);
        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getCompletedAt());

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("completed", progress.getStatus());
        assertEquals(3L, progress.getTotalChunks());
        assertEquals(3L, progress.getCompletedChunks());
        assertEquals(0L, progress.getRemainingChunks());
        assertEquals(100.0, progress.getPercentComplete(), 0.001);
        assertEquals(30L, progress.getTotalRowsAffected());
        long elapsed = completedElapsedMs(runId);
        assertTrue(elapsed > 0, "the run has a measurable duration");
        assertEquals(elapsed, progress.getElapsedMs());
        double seconds = elapsed / 1000.0;
        assertEquals(3.0 / seconds, progress.getChunksPerSecond(), 0.001);
        assertEquals(30.0 / seconds, progress.getRowsPerSecond(), 0.001);
        assertEquals(0L, progress.getEstimatedRemainingMs(),
                "a finished run has nothing remaining");
        assertEquals(run.getCompletedAt(), progress.getEstimatedCompletionAt(),
                "the estimate for a finished run is its completion time");
    }

    /**
     * A single-chunk run that changed no rows reports a zero row rate rather
     * than dividing by zero or returning NULL.
     */
    @Test
    void reportsACompletedSingleZeroRowChunk() throws Exception {
        String label = uniqueLabel("progress-zero-rows");
        long runId = populateSingle(label, 2, 7L, 8L);
        Thread.sleep(50);
        completeBoundary(runId, 0L, 0L);
        completeRun(runId);
        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getCompletedAt());

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("completed", progress.getStatus());
        assertEquals(1L, progress.getTotalChunks());
        assertEquals(1L, progress.getCompletedChunks());
        assertEquals(100.0, progress.getPercentComplete(), 0.001);
        assertEquals(0L, progress.getTotalRowsAffected());
        long elapsed = completedElapsedMs(runId);
        assertTrue(elapsed > 0, "the run has a measurable duration");
        assertEquals(1.0 / (elapsed / 1000.0), progress.getChunksPerSecond(), 0.001);
        assertEquals(0.0, progress.getRowsPerSecond(), 0.001,
                "a productive chunk with zero rows reports a zero rate");
        assertEquals(0L, progress.getEstimatedRemainingMs());
    }

    /**
     * An empty run whose boundaries are calculated reports 100% on the way to
     * completion, and stays 100% once completed.
     */
    @Test
    void reportsAZeroChunkRun() {
        String label = uniqueLabel("progress-zero-chunks");
        long runId = populateSingle(label, 2);

        MigrationRunProgressRecord active = progress(runId);
        assertEquals("active", active.getStatus());
        assertEquals(0L, active.getTotalChunks());
        assertEquals(0L, active.getCompletedChunks());
        assertEquals(0L, active.getRemainingChunks());
        assertEquals(100.0, active.getPercentComplete(), 0.001,
                "an empty run with calculated boundaries has nothing left");
        assertNull(active.getChunksPerSecond(),
                "zero completed chunks cannot produce a rate");
        assertNull(active.getRowsPerSecond());
        assertEquals(0L, active.getEstimatedRemainingMs());
        assertNotNull(active.getEstimatedCompletionAt());

        completeRun(runId);
        MigrationRunProgressRecord completed = progress(runId);
        assertEquals("completed", completed.getStatus());
        assertEquals(100.0, completed.getPercentComplete(), 0.001);
        assertEquals(migrationRun(runId).getCompletedAt(), completed.getEstimatedCompletionAt());
    }

    /**
     * A cancelled run reports its completed fraction, no forward ETA, and the
     * cancelled state on the unclaimed boundaries.
     */
    @Test
    void reportsACancelledRun() {
        String label = uniqueLabel("progress-cancelled");
        long runId = populateSingle(label, 2, 1L, 2L, 3L, 4L, 5L);
        completeBoundary(runId, 0L, 4L);
        assertEquals(runId, Routines.cancelMigrationRun(dsl.configuration(), label));

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("cancelled", progress.getStatus());
        assertNotNull(progress.getCancelledAt());
        assertEquals(3L, progress.getTotalChunks());
        assertEquals(1L, progress.getCompletedChunks());
        assertEquals(2L, progress.getRemainingChunks());
        assertEquals(33.33, progress.getPercentComplete(), 0.01);
        assertNotNull(progress.getChunksPerSecond());
        assertNull(progress.getEstimatedRemainingMs(),
                "a cancelled run will not make more progress");
        assertNull(progress.getEstimatedCompletionAt());

        List<MigrationBoundariesRecord> boundaries = Routines.migrationBoundaries(
                dsl.configuration(), runId);
        List<MigrationBoundariesRecord> pending = boundaries.stream()
                .filter(boundary -> boundary.getCompletedAt() == null)
                .toList();
        assertFalse(pending.isEmpty(), "the run has unclaimed boundaries");
        assertTrue(pending.stream().allMatch(boundary -> boundary.getCancelledAt() != null),
                "every uncompleted boundary (including the terminal one) is cancelled");
        assertTrue(boundaries.stream().anyMatch(boundary -> boundary.getCompletedAt() != null),
                "the completed chunk keeps its completion");
    }

    /**
     * An archived run reports the archived status, 100% and its completion time.
     */
    @Test
    void reportsAnArchivedRun() {
        String label = uniqueLabel("progress-archived");
        long runId = populateSingle(label, 2, 1L, 2L);
        completeBoundary(runId, 0L, 2L);
        completeRun(runId);
        MigrationRunRecord run = migrationRun(runId);
        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));

        MigrationRunProgressRecord progress = progress(runId);

        assertEquals("archived", progress.getStatus());
        assertNotNull(progress.getArchivedAt());
        assertEquals(100.0, progress.getPercentComplete(), 0.001);
        assertEquals(0L, progress.getEstimatedRemainingMs());
        assertEquals(run.getCompletedAt(), progress.getEstimatedCompletionAt());
    }

    /**
     * A partition-wise run reports its aggregate progress, and the public
     * boundary listing exposes the leaf, row count and duration of each chunk.
     */
    @Test
    void reportsAPartitionWiseRunAndItsBoundaries() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("progress-partition-wise");
        long runId = populatePartitionWise(label, 2);
        calculateRun(runId);
        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, SCHEMA, TABLE, label,
                2, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, true, 3, 1000);

        MigrationRunProgressRecord progress = progress(runId);
        assertEquals("completed", progress.getStatus());
        assertTrue(progress.getPartitionWise());
        assertEquals(5L, progress.getTotalChunks());
        assertEquals(5L, progress.getCompletedChunks());
        assertEquals(8L, progress.getTotalRowsAffected(),
                "each partition row is updated exactly once");
        assertEquals(100.0, progress.getPercentComplete(), 0.001);

        List<MigrationBoundariesRecord> boundaries = Routines.migrationBoundaries(
                dsl.configuration(), runId);
        assertEquals(progress.getTotalChunks() + 1, boundaries.size(),
                "the chunk starts plus the terminal high-water boundary");
        long rows = 0;
        for (MigrationBoundariesRecord boundary : boundaries) {
            assertNotNull(boundary.getLeafOrdinal(),
                    "every partition-wise boundary names its leaf");
            if (boundary.getCompletedAt() == null) {
                assertNull(boundary.getRowsAffected(),
                        "the terminal boundary has no row count");
                assertNull(boundary.getDurationMs());
            } else {
                assertNotNull(boundary.getRowsAffected());
                assertNotNull(boundary.getDurationMs());
                rows += boundary.getRowsAffected();
            }
        }
        assertEquals(8L, rows, "the boundary row counts add up to the run total");

        // duration_ms is derived from the two boundary timestamps, in whole
        // milliseconds, so pin an exact value instead of only non-NULL.
        // The terminal boundary is never claimed, so its timestamps are still
        // unset (setting completed_at twice is rejected as immutable).
        dsl.execute("UPDATE dml_utils_data.migration_boundary"
                + " SET started_at = timestamptz '2026-01-01 00:00:00+00',"
                + " completed_at = timestamptz '2026-01-01 00:00:01.25+00',"
                + " rows_affected = 0"
                + " WHERE run_id = ? AND completed_at IS NULL", runId);
        assertEquals(1250L, Routines.migrationBoundaries(dsl.configuration(), runId)
                        .getLast().getDurationMs(),
                "duration_ms is the completed-started difference in milliseconds");
    }

    /**
     * Creates a non-partition-wise run over the bigint fixture and returns its id.
     *
     * @param label     the run label
     * @param chunkSize the chunk size
     * @param ids       the driving-table ids to seed
     * @return the run id
     */
    private long populateSingle(String label, int chunkSize, long... ids) {
        dsl.truncate(TEST_BIGINT).execute();
        seedBigint(ids);
        return populateSingleRelation(label, TEMPLATE, chunkSize);
    }

    /**
     * Creates a partition-wise run over the range fixture and returns its id.
     *
     * @param label     the run label
     * @param chunkSize the chunk size
     * @return the run id
     */
    private long populatePartitionWise(String label, int chunkSize) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(), SCHEMA, TABLE, label,
                        TEMPLATE, chunkSize, 1, ChunkingStrategy.primary_key, "t", 0, null,
                        true, 3, 1000);
    }


    /**
     * Completes one chunk boundary with a row count.
     *
     * @param runId        the run id
     * @param boundaryNo   the chunk boundary
     * @param rowsAffected the recorded row count
     */
    private void completeBoundary(long runId, long boundaryNo, long rowsAffected) {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .completeMigrationBoundary(dsl.configuration(), runId, boundaryNo,
                        OffsetDateTime.now(), rowsAffected);
    }

    /**
     * Marks the run complete once all its chunks are completed.
     *
     * @param runId the run id
     */
    private void completeRun(long runId) {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .completeMigrationRun(dsl.configuration(), runId);
    }

    /**
     * The database's own duration of a completed run, read independently of the
     * view under test.
     *
     * @param runId the run id
     * @return the completed-to-started difference in milliseconds
     */
    private long completedElapsedMs(long runId) {
        return dsl.fetchOne("""
                        SELECT (extract(epoch FROM completed_at - started_at) * 1000)::bigint
                        FROM dml_utils_data.migration_run
                        WHERE run_id = ?
                        """, runId)
                .get(0, Long.class);
    }

    /**
     * The database's own elapsed time for the run, in milliseconds, read
     * independently of the view under test.
     *
     * @param runId the run id
     * @return the elapsed milliseconds
     */
    private long databaseElapsedMs(long runId) {
        return dsl.fetchOne("""
                        SELECT (extract(epoch FROM
                                pg_catalog.clock_timestamp() - started_at) * 1000)::bigint
                        FROM dml_utils_data.migration_run
                        WHERE run_id = ?
                        """, runId)
                .get(0, Long.class);
    }

    /**
     * Reads the single progress row for a run.
     *
     * @param runId the run id
     * @return the progress row
     */
    private MigrationRunProgressRecord progress(long runId) {
        return Routines.migrationRunProgress(dsl.configuration(), runId).getFirst();
    }

}
