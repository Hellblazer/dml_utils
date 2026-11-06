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
 * Verifies {@code dml_utils_lib.complete_migration_boundary}: a NULL started_at
 * is rejected with {@code 22023}, and a caller-supplied future timestamp is
 * clamped so it cannot violate the boundary's time-order check and roll back an
 * already-executed chunk.
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

        assertSqlState("22023", () -> Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, null));
    }

    /**
     * A future started_at is clamped to the completion timestamp, so the
     * boundary completes and the run counter advances.
     */
    @Test
    void clampsAFutureStartedAtToTheCompletionTime() {
        seedBigint(1, 2, 3);
        long runId = populate();

        Routines.completeMigrationBoundary(dsl.configuration(), runId, 0L,
                OffsetDateTime.now().plusHours(1));

        MigrationBoundaryRecord boundary = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .fetchOne();
        assertNotNull(boundary.getCompletedAt(), "the boundary should be completed");
        assertFalse(boundary.getStartedAt().isAfter(boundary.getCompletedAt()),
                "started_at must not follow completed_at");

        assertEquals(1L, dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED));
    }

    private long populate() {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                uniqueLabel("complete-boundary"), "SELECT 1", 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false);
    }
}
