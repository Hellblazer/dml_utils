package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.complete_migration_boundary}: it validates the
 * start timestamp and row count, records the boundary completion and advances
 * the run counters, and clamps a caller-supplied future timestamp so it cannot
 * violate the boundary's time-order check and roll back an already-executed
 * chunk.
 */
class CompleteMigrationBoundaryTest extends PostgresTestBase {

    /**
     * Empties the fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A NULL started_at is rejected before the update, with a parameter error
     * rather than a bare check-constraint violation.
     */
    @Test
    void rejectsANullStartedAt() {
        seedBigint(1, 2, 3);
        long runId = populate();

        assertDomainViolation(() -> Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, null, 0L));
    }

    /**
     * A NULL row count is rejected before the update.
     */
    @Test
    void rejectsANullRowsAffected() {
        seedBigint(1, 2, 3);
        long runId = populate();

        assertDomainViolation(() -> Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, OffsetDateTime.now(), null));
    }

    /**
     * A negative row count is rejected before the update.
     */
    @Test
    void rejectsANegativeRowsAffected() {
        seedBigint(1, 2, 3);
        long runId = populate();

        assertDomainViolation(() -> Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, OffsetDateTime.now(), -1L));
    }

    /**
     * A future started_at is clamped to the completion timestamp, so the
     * boundary completes and the run counters advance.
     */
    @Test
    void clampsAFutureStartedAtToTheCompletionTime() {
        seedBigint(1, 2, 3);
        long runId = populate();

        Routines.completeMigrationBoundary(dsl.configuration(), runId, 0L,
                OffsetDateTime.now().plusHours(1), 3L);

        MigrationBoundaryRecord boundary = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .fetchOne();
        assertNotNull(boundary.getCompletedAt(), "the boundary should be completed");
        assertFalse(boundary.getStartedAt().isAfter(boundary.getCompletedAt()),
                "started_at must not follow completed_at");
        assertEquals(3L, boundary.getRowsAffected(), "the row count is recorded");

        assertEquals(1L, dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED));
        assertEquals(3L, dsl.select(MIGRATION_RUN.TOTAL_ROWS_AFFECTED)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_ROWS_AFFECTED));
    }

    private long populate() {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                uniqueLabel("complete-boundary"), "SELECT 1", 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
    }
}
