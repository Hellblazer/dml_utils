package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A completed boundary keeps its NULL cancelled_at, while every pending
     * boundary, including the terminal one, is cancelled.
     */
    @Test
    void marksTheRunAndItsPendingBoundariesOnly() {
        seedBigint(1, 2, 3);
        long runId = populate(1);
        Routines.completeMigrationBoundary(dsl.configuration(), runId, 0L,
                OffsetDateTime.now());

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        MigrationRunRecord run = run(runId);
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
     * A second call reuses the existing timestamps instead of rewriting them.
     */
    @Test
    void isIdempotent() {
        seedBigint(1, 2, 3);
        long runId = populate(1);
        Routines.markMigrationRunCancelled(dsl.configuration(), runId);
        OffsetDateTime runCancelledAt = run(runId).getCancelledAt();
        OffsetDateTime boundaryCancelledAt = dsl.select(MIGRATION_BOUNDARY.CANCELLED_AT)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .fetchOne(MIGRATION_BOUNDARY.CANCELLED_AT);

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        assertEquals(runCancelledAt, run(runId).getCancelledAt());
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
        long runId = populate(1);
        for (long boundaryNo = 0; boundaryNo <= 2; boundaryNo++) {
            Routines.completeMigrationBoundary(dsl.configuration(), runId, boundaryNo,
                    OffsetDateTime.now());
        }
        Routines.completeMigrationRun(dsl.configuration(), runId);

        Routines.markMigrationRunCancelled(dsl.configuration(), runId);

        assertNotNull(run(runId).getCompletedAt());
        assertNull(run(runId).getCancelledAt());
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

    private long populate(int chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                uniqueLabel("mark-cancelled"), "SELECT 1", chunkSize, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false);
    }

    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
