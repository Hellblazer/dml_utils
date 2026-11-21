package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_data.reject_migration_boundary_update} freezes a
 * boundary's {@code started_at} once {@code completed_at} is set, so the duration
 * derived from the pair cannot be rewritten after the chunk completed.
 */
class MigrationBoundaryStartedAtGuardTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String FIXTURE_TABLE = "test_bigint";

    /**
     * Asserts that a statement is rejected with the given SQLSTATE and a message
     * containing the given text.
     *
     * @param expectedState the expected SQLSTATE
     * @param messagePart   text the error message must contain
     * @param statement     the statement to run
     */
    private static void assertRejected(String expectedState, String messagePart,
                                       Executable statement) {
        DataAccessException error = assertThrows(DataAccessException.class, statement::execute);
        assertEquals(expectedState, sqlState(error),
                () -> "unexpected SQLSTATE: " + error.getMessage());
        assertTrue(error.getMessage().contains(messagePart),
                () -> "unexpected message: " + error.getMessage());
    }

    /**
     * Removes the runs (and, by cascade, their boundaries) left by earlier tests.
     */
    @BeforeEach
    void resetRuns() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
    }

    /**
     * Completion writes {@code started_at} with {@code completed_at}; the pair is
     * then frozen.
     */
    @Test
    void freezesStartedAtOnceCompleted() {
        long runId = insertRun("boundary-started-at-" + UUID.randomUUID(),
                FIXTURE_SCHEMA, FIXTURE_TABLE);
        insertBoundary(runId);

        OffsetDateTime completedAt = OffsetDateTime.now();
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, completedAt.minusSeconds(1))
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, completedAt)
                .set(MIGRATION_BOUNDARY.ROWS_AFFECTED, 1L)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .execute();

        assertRejected("22023", "started_at is immutable once completed",
                () -> dsl.update(MIGRATION_BOUNDARY)
                        .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.now().minusHours(1))
                        .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                        .execute());
        assertRejected("22023", "started_at is immutable once completed",
                () -> dsl.update(MIGRATION_BOUNDARY)
                        .set(MIGRATION_BOUNDARY.STARTED_AT, (OffsetDateTime) null)
                        .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                        .execute());
    }

    /**
     * A boundary that has not completed keeps its {@code started_at} writable, so
     * the guard cannot block a chunk that is still in progress.
     */
    @Test
    void allowsStartedAtBeforeCompletion() {
        long runId = insertRun("boundary-started-at-pending-" + UUID.randomUUID(),
                FIXTURE_SCHEMA, FIXTURE_TABLE);
        insertBoundary(runId);

        assertEquals(1, dsl.update(MIGRATION_BOUNDARY)
                        .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.now())
                        .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                        .execute(),
                "setting started_at on a pending boundary applies to its row");
        assertEquals(1, dsl.update(MIGRATION_BOUNDARY)
                        .set(MIGRATION_BOUNDARY.STARTED_AT, (OffsetDateTime) null)
                        .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                        .execute(),
                "clearing started_at before completion applies to its row");
    }

    /**
     * Inserts one pending boundary directly, so the trigger sees it without the
     * boundary routines.
     *
     * @param runId the owning run
     */
    private void insertBoundary(long runId) {
        dsl.insertInto(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, null, null, null))
                .execute();
    }
}
