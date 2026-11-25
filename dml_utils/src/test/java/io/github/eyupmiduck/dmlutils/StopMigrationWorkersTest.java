package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.stop_migration_workers}: it is a no-op for an
 * empty pid array, and rejects invalid handle arrays (including NULL elements)
 * or wait timeouts before touching the pg_background handles.
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
     * The loops index both arrays from 1, so the argument domains reject a
     * zero-based or multidimensional array with a domain violation
     * ({@code 23514}) instead of addressing the wrong element.
     */
    @Test
    void rejectsZeroBasedOrMultidimensionalArrays() {
        assertSqlState("23514", () -> dsl.execute(
                "SELECT dml_utils_lib.stop_migration_workers("
                        + "'[0:0]={1}'::integer[], '[0:0]={1}'::bigint[], false, 100)"));
        assertSqlState("23514", () -> dsl.execute(
                "SELECT dml_utils_lib.stop_migration_workers("
                        + "'{1}'::integer[], '[0:0]={1}'::bigint[], false, 100)"));
        assertSqlState("23514", () -> dsl.execute(
                "SELECT dml_utils_lib.stop_migration_workers("
                        + "'{{1}}'::integer[], '{{1}}'::bigint[], false, 100)"));
    }

    /**
     * A NULL element in either handle array is a domain violation
     * ({@code 23514}): the pg_background handle helpers are {@code STRICT}, so a
     * NULL pid or cookie would make the call a silent no-op.
     */
    @Test
    void rejectsNullHandleElements() {
        assertSqlState("23514", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{null}, new Long[]{1L}, false, 100));
        assertSqlState("23514", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1}, new Long[]{null}, false, 100));
        assertSqlState("23514", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1, null}, new Long[]{1L, 2L}, false, 100));
        assertSqlState("23514", () -> Routines.stopMigrationWorkers(
                dsl.configuration(), new Integer[]{1, 2}, new Long[]{1L, null}, false, 100));
        assertSqlState("23514", () -> dsl.execute(
                "SELECT dml_utils_lib.stop_migration_workers("
                        + "'{NULL}'::integer[], '{1}'::bigint[], false, 100)"));
        assertSqlState("23514", () -> dsl.execute(
                "SELECT dml_utils_lib.stop_migration_workers("
                        + "'{1}'::integer[], '{NULL}'::bigint[], false, 100)"));
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
