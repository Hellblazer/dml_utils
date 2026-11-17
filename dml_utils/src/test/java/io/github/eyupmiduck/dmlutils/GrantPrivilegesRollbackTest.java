package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.GrantAssertions;
import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the {@code 999-grant-privileges} rollback mirrors the forward
 * grants: it delegates the per-function comparison to
 * {@link GrantAssertions#assertGrantRollbackMirrors}, and it must not re-open
 * PUBLIC's default privileges, which the forward changeset never changed (they
 * belong to {@code 007-default-privileges}).
 */
class GrantPrivilegesRollbackTest extends PostgresTestBase {

    private static final String FORWARD =
            "db/changelog/changes/sql_changes/999-grant-privileges.sql";
    private static final String ROLLBACK =
            "db/changelog/changes/rollback/999-grant-privileges-rollback.sql";

    /**
     * Every function granted to {@code dml_utils_caller} in the forward
     * changeset is revoked from the caller in the rollback with the same
     * signature, and every function revoked from {@code PUBLIC} has its
     * {@code PUBLIC} grant restored.
     */
    @Test
    void rollbackMirrorsTheForwardGrants() throws IOException {
        GrantAssertions.assertGrantRollbackMirrors(FORWARD, "dml_utils_caller", ROLLBACK);
    }

    /**
     * The rollback leaves the default ACLs alone: forward 999 never changed them,
     * so restoring PUBLIC's default EXECUTE/USAGE here would silently make every
     * future routine and type world-usable even after the rollback.
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
     * Sentinel thrown to roll back the transactional rollback-script run.
     */
    private static final class RollbackTransaction extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RollbackTransaction() {
            super("roll back the transactional rollback-script run");
        }
    }
}
