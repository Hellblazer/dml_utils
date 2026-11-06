package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.stop_migration_workers}: it is a no-op for an
 * empty pid array, and rejects invalid handle arrays or wait timeouts before
 * touching the pg_background handles.
 */
class StopMigrationWorkersTest extends PostgresTestBase {

    /**
     * An empty pid array is a no-op.
     */
    @Test
    void noOpForAnEmptyPidArray() {
        assertDoesNotThrow(() -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[0], new Long[0], false, null));
    }

    /**
     * A missing or short cookie array would make every handle operation a silent
     * no-op, so it is rejected with {@code 22023}.
     */
    @Test
    void rejectsMissingOrMismatchedCookieArrays() {
        assertSqlState("22023", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1}, null, false, 100));
        assertSqlState("22023", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1, 2}, new Long[]{1L}, false, 100));
    }

    /**
     * A non-positive wait timeout is rejected; NULL is the explicit
     * wait-indefinitely spelling.
     */
    @Test
    void rejectsANonPositiveWaitTimeout() {
        assertSqlState("22023", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1}, new Long[]{1L}, false, 0));
        assertSqlState("22023", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1}, new Long[]{1L}, false, -1));
    }
}
