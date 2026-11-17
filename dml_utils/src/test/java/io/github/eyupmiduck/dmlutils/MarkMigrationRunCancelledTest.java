package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.mark_migration_run_cancelled}: it stamps the run
 * and every still-pending boundary, leaves a completed boundary untouched, is
 * idempotent, does nothing for a completed run, and tolerates a missing run.
 */
class MarkMigrationRunCancelledTest extends PostgresTestBase {

    /**
     * Empties the fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndBigint();
    }

    /**
     * A completed boundary keeps its NULL cancelled_at, while every pending
     * boundary, including the terminal one, is cancelled.
     */
    @Test
    void marksTheRunAndItsPendingBoundariesOnly() {
        seedBigint(1, 2, 3);
        long runId = populateSingleRelationWithLabelPrefix("mark-cancelled", 1);
        Routines.completeMigrationBoundary(dsl.configuration(), runId, 0L,
                OffsetDateTime.now(), 0L);

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getCancelledAt());
        assertNull(run.getCompletedAt());
        assertEquals(1L, run.getTotalChunksCompleted(),
                "cancelling does not change the completed count");

        List<Long> cancelled = dsl.select(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull())
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_NO);
        assertEquals(List.of(1L, 2L, 3L), cancelled,
                "pending starts and the terminal boundary are cancelled; the completed one is not");
    }

    /**
     * Cancelling clears a pause, so a paused run becomes terminal rather than
     * staying paused forever.
     */
    @Test
    void clearsAPausedRun() {
        seedBigint(1, 2);
        long runId = populateSingleRelationWithLabelPrefix("mark-cancelled", 1);
        dsl.execute("UPDATE dml_utils_data.migration_run"
                + " SET paused_at = pg_catalog.clock_timestamp() WHERE run_id = ?", runId);

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        assertNull(migrationRun(runId).getPausedAt(), "cancelling clears the pause");
        assertNotNull(migrationRun(runId).getCancelledAt());
    }

    /**
     * A second call reuses the existing timestamps instead of rewriting them.
     */
    @Test
    void isIdempotent() {
        seedBigint(1, 2, 3);
        long runId = populateSingleRelationWithLabelPrefix("mark-cancelled", 1);
        Routines.markMigrationRunCancelled(dsl.configuration(), runId);
        OffsetDateTime runCancelledAt = migrationRun(runId).getCancelledAt();
        OffsetDateTime boundaryCancelledAt = dsl.select(MIGRATION_BOUNDARY.CANCELLED_AT)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .fetchOne(MIGRATION_BOUNDARY.CANCELLED_AT);

        assertNotNull(runCancelledAt, "the first call stamps the run");
        assertNotNull(boundaryCancelledAt, "the first call stamps the boundary");

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        assertEquals(runCancelledAt, migrationRun(runId).getCancelledAt());
        assertEquals(boundaryCancelledAt,
                dsl.select(MIGRATION_BOUNDARY.CANCELLED_AT)
                        .from(MIGRATION_BOUNDARY)
                        .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                        .fetchOne(MIGRATION_BOUNDARY.CANCELLED_AT));
    }

    /**
     * A completed run is left untouched, including its boundaries.
     */
    @Test
    void leavesACompletedRunUntouched() {
        seedBigint(1, 2, 3);
        long runId = populateSingleRelationWithLabelPrefix("mark-cancelled", 1);
        for (long boundaryNo = 0; boundaryNo <= 2; boundaryNo++) {
            Routines.completeMigrationBoundary(dsl.configuration(), runId, boundaryNo,
                    OffsetDateTime.now(), 0L);
        }
        Routines.completeMigrationRun(dsl.configuration(), runId);

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        assertNotNull(migrationRun(runId).getCompletedAt());
        assertNull(migrationRun(runId).getCancelledAt());
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull())));
    }

    /**
     * A missing run is a no-op rather than an error.
     */
    @Test
    void toleratesAMissingRun() {
        Routines.markMigrationRunCancelled(dsl.configuration(), -1L);
    }


}
