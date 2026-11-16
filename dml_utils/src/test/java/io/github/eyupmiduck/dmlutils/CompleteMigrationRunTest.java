package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.complete_migration_run}: it completes a run only
 * once its boundaries are calculated and every chunk is complete, clears a pause
 * in the same update, is idempotent, leaves a cancelled run unchanged, and
 * rejects a NULL run id.
 */
class CompleteMigrationRunTest extends PartitionTestBase {

    /**
     * Empties the migration metadata and both driving fixtures between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A single-relation run whose boundaries are calculated completes once every
     * chunk has completed, and a completed run keeps its original timestamp on a
     * second call.
     */
    @Test
    void completesACalculatedRunAndIsIdempotent() {
        seedBigint(1, 2, 3);
        long runId = populateSingleRelation(1);
        completeEveryChunk(runId);

        Routines.completeMigrationRun(dsl.configuration(), runId);

        MigrationRunRecord run = run(runId);
        assertNotNull(run.getCompletedAt(), "every chunk is complete, so the run completes");
        OffsetDateTime completedAt = run.getCompletedAt();

        Routines.completeMigrationRun(dsl.configuration(), runId);

        assertEquals(completedAt, run(runId).getCompletedAt(),
                "a second call must not move the completion timestamp");
    }

    /**
     * A partition-wise run is committed by phase 1 with total_chunks = 0 and no
     * boundaries_calculated_at milestone, so completion must wait for the boundary
     * calculation instead of treating 0 completed of 0 chunks as done.
     */
    @Test
    void doesNotCompleteARunBeforeItsBoundariesAreCalculated() {
        long runId = populatePartitionWise(uniqueLabel("complete-calculating"),
                PARTITIONED_RANGE, "SELECT 1", ChunkingStrategy.primary_key, 2, null);

        Routines.completeMigrationRun(dsl.configuration(), runId);

        MigrationRunRecord run = run(runId);
        assertNull(run.getCompletedAt(), "a still-calculating run must not complete");
        assertNull(run.getBoundariesCalculatedAt(), "the manifest-only phase has no milestone");
        assertEquals(0L, run.getTotalChunks(), "no boundaries are calculated yet");
    }

    /**
     * A cancelled run is terminal and is left untouched by completion.
     */
    @Test
    void leavesACancelledRunUnchanged() {
        seedBigint(1, 2, 3);
        long runId = populateSingleRelation(1);
        completeEveryChunk(runId);
        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        Routines.completeMigrationRun(dsl.configuration(), runId);

        MigrationRunRecord run = run(runId);
        assertNull(run.getCompletedAt(), "a cancelled run must not complete");
        assertNotNull(run.getCancelledAt(), "the cancellation is kept");
    }

    /**
     * Completion clears a pause in the same update, so a paused-but-finished run
     * ends up terminal with no pause flag.
     */
    @Test
    void clearsAPauseOnCompletion() {
        seedBigint(1, 2, 3);
        long runId = populateSingleRelation(1);
        completeEveryChunk(runId);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.PAUSED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        Routines.completeMigrationRun(dsl.configuration(), runId);

        MigrationRunRecord run = run(runId);
        assertNotNull(run.getCompletedAt(), "the finished run completes");
        assertNull(run.getPausedAt(), "completion clears the pause flag");
    }

    /**
     * A NULL run id is a caller argument error, not a silent no-op.
     */
    @Test
    void rejectsANullRunId() {
        assertDomainViolation(
                () -> Routines.completeMigrationRun(dsl.configuration(), null));
    }

    private long populateSingleRelation(int chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                uniqueLabel("complete-run"), "SELECT 1", chunkSize, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
    }

    private void completeEveryChunk(long runId) {
        assertTrue(totalChunks(runId) > 0, "the fixture should produce at least one chunk");
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED, totalChunks(runId))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertFalse(runCompleted(runId), "setting the counter alone must not complete the run");
    }

    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
