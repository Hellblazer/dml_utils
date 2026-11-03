package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.GrantAssertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the caller grants: {@code dml_utils_caller} can execute every
 * callable routine (except trigger functions) and use the migration key type,
 * and no routine or type is usable by {@code PUBLIC}. The ACL invariants
 * delegate to the shared {@link GrantAssertions}.
 */
class GrantPrivilegesTest extends PostgresTestBase {

    private static final List<String> ROUTINE_SCHEMAS =
            List.of("dml_utils", "dml_utils_lib", "dml_utils_data");
    private static final List<String[]> APPLICATION_TYPES = List.of(
            new String[]{"dml_utils_data", "migration_key"},
            new String[]{"dml_utils_data", "non_null_text"},
            new String[]{"dml_utils_data", "positive_integer"},
            new String[]{"dml_utils_data", "non_negative_integer"},
            new String[]{"dml_utils_data", "non_null_boolean"},
            new String[]{"dml_utils_data", "chunking_strategy"});
    private static final String CALLER = "dml_utils_caller";

    /**
     * No routine in the application schemas keeps PostgreSQL's default
     * {@code PUBLIC} execute privilege.
     */
    @Test
    void noRoutineIsExecutableByPublic() throws Exception {
        try (Connection connection = openTestConnection()) {
            GrantAssertions.assertNoRoutineExecutableByPublic(connection, ROUTINE_SCHEMAS);
        }
    }

    /**
     * The application's types and domains do not retain PostgreSQL's default
     * {@code PUBLIC} usage privilege.
     */
    @Test
    void applicationTypesAreNotUsableByPublic() throws Exception {
        try (Connection connection = openTestConnection()) {
            for (String[] type : APPLICATION_TYPES) {
                GrantAssertions.assertTypeIsNotUsableByPublic(connection, type[0], type[1]);
            }
        }
    }

    /**
     * The caller role can execute every routine it is meant to invoke: every
     * procedure, and every function except trigger functions (whose EXECUTE is
     * deliberately granted to no one).
     */
    @Test
    void callerCanExecuteEveryCallableRoutine() throws Exception {
        try (Connection connection = openTestConnection()) {
            GrantAssertions.assertRoleCanExecuteEveryCallableRoutine(connection, ROUTINE_SCHEMAS, CALLER);
        }
    }

    /**
     * The caller has an explicit {@code USAGE} grant on the migration key type
     * (asserted on the ACL, not via {@code has_type_privilege}, which would also
     * accept the default PUBLIC grant) and can select the migration tables.
     */
    @Test
    void callerHasExplicitUsageOnTheMigrationKeyTypeAndCanSelectTables() throws Exception {
        try (Connection connection = openTestConnection()) {
            GrantAssertions.assertRoleHasExplicitUsageOnType(connection, "dml_utils_data", "migration_key", CALLER);
        }

        assertEquals(Boolean.TRUE, dsl.fetchOne(
                        "SELECT pg_catalog.has_table_privilege('dml_utils_caller',"
                                + " 'dml_utils_data.migration_boundary', 'SELECT')")
                .get(0, Boolean.class), "the caller should be able to select migration_boundary");
    }
}
