package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.PrivilegeContract;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the caller grants and default-privilege hardening, inheriting the
 * shared {@link PrivilegeContract}: no routine or application type is usable by
 * {@code PUBLIC}, the caller can execute every callable routine and use every
 * configured type, a routine or type created later does not regain {@code PUBLIC}
 * access, and the grant rollback mirrors the forward grants.
 *
 * <p>The dml_utils-specific checks keep the migration-table SELECT grants and
 * prove that the 999 rollback does not re-open the default ACLs.
 */
class PrivilegeTest extends PrivilegeContract {

    private static final String ROLLBACK =
            "db/changelog/changes/rollback/999-grant-privileges-rollback.sql";

    /**
     * Splits a SQL script into statements on top-level semicolons.
     *
     * @param script the script
     * @return the statements
     */
    private static List<String> splitStatements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            int comment = line.indexOf("--");
            withoutComments.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return List.of(withoutComments.toString().split(";"));
    }

    /**
     * Every domain, enum, composite type and implicit table row type the caller
     * may use.
     *
     * @return the application types
     */
    @Override
    protected Collection<TypeRef> applicationTypes() {
        return List.of(
                new TypeRef("dml_utils_data", "migration_key"),
                new TypeRef("dml_utils_data", "non_null_text"),
                new TypeRef("dml_utils_data", "positive_integer"),
                new TypeRef("dml_utils_data", "non_negative_integer"),
                new TypeRef("dml_utils_data", "non_null_boolean"),
                new TypeRef("dml_utils_data", "non_null_bigint"),
                new TypeRef("dml_utils_data", "non_negative_bigint"),
                new TypeRef("dml_utils_data", "positive_bigint"),
                new TypeRef("dml_utils_data", "non_null_timestamptz"),
                new TypeRef("dml_utils_data", "non_null_oid"),
                new TypeRef("dml_utils_data", "non_null_element_integer_array"),
                new TypeRef("dml_utils_data", "non_null_element_bigint_array"),
                new TypeRef("dml_utils_data", "non_null_element_text_array"),
                new TypeRef("dml_utils_data", "non_null_element_name_array"),
                new TypeRef("dml_utils_data", "chunking_strategy"),
                new TypeRef("dml_utils_data", "migration_run"),
                new TypeRef("dml_utils_data", "migration_boundary"),
                new TypeRef("dml_utils_data", "migration_error"),
                new TypeRef("dml_utils_data", "migration_run_leaf"));
    }

    /**
     * The caller can select each migration table, which the chunk routines
     * require directly.
     */
    @Test
    void callerCanSelectTheMigrationTables() {
        for (String table : List.of("migration_run", "migration_boundary",
                "migration_error", "migration_run_leaf")) {
            assertEquals(Boolean.TRUE, dsl.fetchOne(
                                    "SELECT pg_catalog.has_table_privilege(?, ?, 'SELECT')",
                                    PostgresTestBase.TEST_USER, "dml_utils_data." + table)
                            .get(0, Boolean.class),
                    "the caller should be able to select " + table);
        }
    }

    /**
     * The 999 rollback leaves the default ACLs alone: forward 999 never changed
     * them, so restoring PUBLIC's default EXECUTE/USAGE here would silently make
     * every future routine and type world-usable even after the rollback.
     *
     * @throws Exception if the rollback script or connection fails
     */
    @Test
    void rollbackDoesNotRestorePublicDefaults() throws Exception {
        RuntimeException rollback = new RollbackTransaction();
        try (Connection owner = openOwnerConnection()) {
            DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
            try {
                ownerDsl.transaction(config -> {
                    DSLContext tx = DSL.using(config);
                    // The rollback is a long GRANT/REVOKE script. Strip line
                    // comments first (a comment may contain a semicolon), then send
                    // one statement at a time, the same way MigrationTablesTest
                    // executes rollback scripts: no dollar-quoted body or literal
                    // semicolon occurs in this file.
                    String script = TestResources.read(ROLLBACK);
                    for (String statement : splitStatements(script)) {
                        if (!statement.isBlank()) {
                            tx.execute(statement);
                        }
                    }
                    assertEquals(0, publicDefaultAclGrants(tx),
                            "the 999 rollback must not grant default privileges to PUBLIC");
                    throw rollback;
                });
                fail("the rollback transaction should have been rolled back");
            } catch (RuntimeException error) {
                if (error != rollback && error.getCause() != rollback) {
                    throw error;
                }
            }
        }
    }

    private int publicDefaultAclGrants(DSLContext context) {
        return context.fetchOne("""
                        SELECT count(*)
                        FROM pg_catalog.pg_default_acl AS d
                        LEFT JOIN pg_catalog.pg_namespace AS n ON n.oid = d.defaclnamespace
                        CROSS JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) AS a
                        WHERE (d.defaclnamespace = 0
                               OR n.nspname IN ('dml_utils', 'dml_utils_lib', 'dml_utils_data'))
                          -- The rollback under test must not re-open the owner's
                          -- database-wide defaults either (defaclnamespace = 0);
                          -- that is exactly what 007 revoked.
                          AND d.defaclrole = 'dml_utils_owner'::pg_catalog.regrole
                          AND d.defaclobjtype IN ('f', 'T')
                          AND a.grantee = 0
                        """)
                .get(0, Integer.class);
    }

    /**
     * The dml_utils profile, so this contract uses the project's roles, image,
     * changelog and schemas rather than the library defaults.
     *
     * @return the dml_utils profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }

    /**
     * Sentinel thrown to roll back the transactional rollback-script run.
     */
    private static final class RollbackTransaction extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RollbackTransaction() {
            super("roll back the transactional rollback-script run");
        }
    }
}
