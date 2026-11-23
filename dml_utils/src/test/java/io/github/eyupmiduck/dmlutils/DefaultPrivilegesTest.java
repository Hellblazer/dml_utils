package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the default-privilege hardening: changeset 004 revokes PUBLIC's
 * default EXECUTE on functions and USAGE on types for the owner role, it runs
 * before the changesets and includes that create the first types and routines,
 * and the revocation actually applies to newly created objects.
 */
class DefaultPrivilegesTest extends PostgresTestBase {

    private static final String CHANGES = "db/changelog/changes/changes.xml";

    /**
     * The default-privilege changeset is ordered before 005 (domains and the
     * chunking_strategy enum), 006 (the first routine) and the functions include
     * (every other routine). File order is what Liquibase applies, and the id now
     * matches that order.
     */
    @Test
    void defaultPrivilegesAreRevokedBeforeAnyTypeOrRoutineExists() throws Exception {
        String changes = TestResources.read(CHANGES);
        int defaults = changes.indexOf("id=\"004-default-privileges\"");
        int domains = changes.indexOf("id=\"005-create-domains\"");
        int firstRoutine = changes.indexOf("id=\"006-create-migration-key-check\"");
        int remainingRoutines = changes.indexOf("<include file=\"functions.xml\"");

        assertTrue(defaults >= 0, "004-default-privileges should be in changes.xml");
        assertTrue(domains > defaults,
                "004 must run before 005 creates the first types");
        assertTrue(firstRoutine > defaults,
                "004 must run before 006 creates the first routine");
        assertTrue(remainingRoutines > defaults,
                "004 must run before the functions include creates the remaining routines");
    }

    /**
     * The database-wide default ACL for the owner role records the revokes.
     *
     * <p>The entry must be global ({@code defaclnamespace = 0}): a schema-scoped
     * default ACL is added on top of PostgreSQL's hard-wired default, so it can
     * only widen access and its REVOKE records nothing.
     */
    @Test
    void theOwnerHasExplicitGlobalDefaultAcls() {
        int globalDefaults = dsl.fetchOne("""
                        SELECT count(*)
                        FROM pg_catalog.pg_default_acl AS d
                        JOIN pg_catalog.pg_roles AS r ON r.oid = d.defaclrole
                        WHERE r.rolname = 'dml_utils_owner'
                          AND d.defaclnamespace = 0
                          AND d.defaclobjtype IN ('f', 'T')
                        """)
                .get(0, Integer.class);
        assertEquals(2, globalDefaults,
                "004 should record a global default ACL for functions and for types");

        int publicDefaults = dsl.fetchOne("""
                        SELECT count(*)
                        FROM pg_catalog.pg_default_acl AS d
                        JOIN pg_catalog.pg_roles AS r ON r.oid = d.defaclrole
                        CROSS JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) AS a
                        WHERE r.rolname = 'dml_utils_owner'
                          AND d.defaclnamespace = 0
                          AND d.defaclobjtype IN ('f', 'T')
                          AND a.grantee = 0
                        """)
                .get(0, Integer.class);
        assertEquals(0, publicDefaults,
                "PUBLIC must not hold a default EXECUTE/USAGE grant for the owner role");
    }

}
