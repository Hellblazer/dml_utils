package io.github.eyupmiduck.dmlutils;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.UUID;

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

        dsl.execute("""
                UPDATE dml_utils_data.migration_boundary
                SET started_at = pg_catalog.clock_timestamp() - interval '1 second',
                    completed_at = pg_catalog.clock_timestamp(),
                    rows_affected = 1
                WHERE run_id = ?
                  AND boundary_no = 0
                """, runId);

        assertRejected("22023", "started_at is immutable once completed", () -> dsl.execute(
                "UPDATE dml_utils_data.migration_boundary"
                        + " SET started_at = started_at - interval '1 hour'"
                        + " WHERE run_id = ? AND boundary_no = 0", runId));
        assertRejected("22023", "started_at is immutable once completed", () -> dsl.execute(
                "UPDATE dml_utils_data.migration_boundary SET started_at = NULL"
                        + " WHERE run_id = ? AND boundary_no = 0", runId));
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

        dsl.execute("UPDATE dml_utils_data.migration_boundary"
                + " SET started_at = pg_catalog.clock_timestamp()"
                + " WHERE run_id = ? AND boundary_no = 0", runId);
        dsl.execute("UPDATE dml_utils_data.migration_boundary SET started_at = NULL"
                + " WHERE run_id = ? AND boundary_no = 0", runId);
    }

    /**
     * Inserts one pending boundary directly, so the trigger sees it without the
     * boundary routines.
     *
     * @param runId the owning run
     */
    private void insertBoundary(long runId) {
        dsl.execute("""
                INSERT INTO dml_utils_data.migration_boundary
                    (run_id, boundary_no, boundary_id)
                VALUES (?, 0, ROW(ARRAY[1::bigint], NULL, NULL, NULL)::dml_utils_data.migration_key)
                """, runId);
    }
}
