package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_caller_can_select_relation}: a readable
 * relation passes, a missing schema USAGE or table SELECT raises {@code 42501},
 * and a relation whose row-level security the definer-backed scan would bypass is
 * rejected with {@code 55000}.
 */
class AssertCallerCanSelectRelationTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_bigint";

    /**
     * A relation the caller can read passes.
     */
    @Test
    void acceptsAReadableRelation() {
        assertDoesNotThrow(() -> Routines.assertCallerCanSelectRelation(
                dsl.configuration(), SCHEMA, TABLE));
    }

    /**
     * A caller without table-level SELECT is rejected with {@code 42501}.
     *
     * @throws Exception if the grant cannot be restored
     */
    @Test
    void rejectsAMissingTableSelect() throws Exception {
        executeAsOwner("REVOKE SELECT ON " + SCHEMA + "." + TABLE
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.assertCallerCanSelectRelation(
                    dsl.configuration(), SCHEMA, TABLE));
        } finally {
            executeAsOwner("GRANT SELECT ON " + SCHEMA + "." + TABLE
                    + " TO " + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * A caller with table SELECT but no schema USAGE cannot read the relation
     * directly, so the definer-backed check also rejects it with {@code 42501}.
     *
     * @throws Exception if the grant cannot be restored
     */
    @Test
    void rejectsAMissingSchemaUsage() throws Exception {
        executeAsOwner("REVOKE USAGE ON SCHEMA " + SCHEMA
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.assertCallerCanSelectRelation(
                    dsl.configuration(), SCHEMA, TABLE));
        } finally {
            executeAsOwner("GRANT USAGE ON SCHEMA " + SCHEMA
                    + " TO " + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * The definer owns the RLS-enabled relation and bypasses its policies, while
     * the caller does not: the definer-backed scan would read rows the caller
     * cannot see, so the check fails closed with {@code 55000}.
     *
     * @throws Exception if the probe cannot be created or removed
     */
    @Test
    void rejectsRowLevelSecurityTheDefinerWouldBypass() throws Exception {
        createRlsProbe(false);
        try {
            assertSqlState("55000", () -> dsl.execute(
                    "SELECT " + SCHEMA + ".caller_select_rls_probe()"));
        } finally {
            dropRlsProbe();
        }
    }

    /**
     * With FORCE ROW LEVEL SECURITY the definer is subject to the policies too, so
     * the scan cannot see more than the caller and the check passes.
     *
     * @throws Exception if the probe cannot be created or removed
     */
    @Test
    void acceptsRowLevelSecurityForcedOnTheDefiner() throws Exception {
        createRlsProbe(true);
        try {
            assertDoesNotThrow(() -> dsl.execute(
                    "SELECT " + SCHEMA + ".caller_select_rls_probe()"));
        } finally {
            dropRlsProbe();
        }
    }

    /**
     * Creates the RLS fixture table and a superuser-owned definer probe that calls
     * the assertion on it.
     *
     * @param force whether ROW LEVEL SECURITY is forced on the owner
     * @throws Exception if the DDL fails
     */
    private void createRlsProbe(boolean force) throws Exception {
        executeAsOwner("CREATE TABLE " + SCHEMA
                + ".caller_select_rls (id bigint PRIMARY KEY, payload text)");
        executeAsOwner("ALTER TABLE " + SCHEMA + ".caller_select_rls ENABLE ROW LEVEL SECURITY");
        if (force) {
            executeAsOwner("ALTER TABLE " + SCHEMA
                    + ".caller_select_rls FORCE ROW LEVEL SECURITY");
        }
        executeAsOwner("GRANT SELECT ON " + SCHEMA + ".caller_select_rls TO " + TEST_USER);
        executeAsOwner("""
                CREATE FUNCTION dml_utils_fixtures.caller_select_rls_probe()
                    RETURNS void
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                AS $probe$
                BEGIN
                    PERFORM dml_utils_lib.assert_caller_can_select_relation(
                            'dml_utils_fixtures', 'caller_select_rls');
                END
                $probe$
                """);
        executeAsOwner("GRANT EXECUTE ON FUNCTION " + SCHEMA
                + ".caller_select_rls_probe() TO " + TEST_USER);
    }

    /**
     * Removes the probe and its table.
     *
     * @throws Exception if the DDL fails
     */
    private void dropRlsProbe() throws Exception {
        executeAsOwner("DROP FUNCTION IF EXISTS " + SCHEMA + ".caller_select_rls_probe()");
        executeAsOwner("DROP TABLE IF EXISTS " + SCHEMA + ".caller_select_rls");
    }
}
