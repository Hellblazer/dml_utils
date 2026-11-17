package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.PublishedRelease;
import io.github.eyupmiduck.changelogvalidator.testing.UpgradeContract;
import org.junit.jupiter.api.Disabled;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the incremental upgrade from the most recent published release of
 * dml_utils to the current codebase, inheriting the shared
 * {@link UpgradeContract}: the released changelog's SQL is applied to a scratch
 * database, then the current changelog, so an upgrade that only works on an
 * empty database fails here.
 *
 * <p>The previous release is resolved from GitHub Packages automatically. The
 * upgrade configuration shares every value with the regular test base except the
 * master changelog: the upgrade test must apply the plain dml_utils changelog,
 * not the install changelog that bundles ddl_utils.
 */
@Disabled("Suspended for epic dml-ix5: changesets are edited in place while nothing is deployed, so the "
        + "published baseline's changeset checksums no longer match. Restore against the first release cut "
        + "from the cleaned changelog, as done in dml-99x.3 (and dml-b87.13 before it).")
class LiquibaseUpgradeTest extends UpgradeContract {

    /**
     * The current revision, injected by the surefire configuration.
     *
     * <p>There is deliberately no silent fallback: a run outside surefire (an IDE
     * or a direct JUnit launch) must fail with an actionable message instead of
     * resolving the non-existent {@code 0.0.0-SNAPSHOT} baseline.
     *
     * @return the current project revision
     */
    private static String currentVersion() {
        String version = System.getProperty("upgrade.current.version");
        if (version == null || version.isBlank() || "0.0.0-SNAPSHOT".equals(version)) {
            throw new IllegalStateException(
                    "upgrade.current.version must be set to the current <revision>; "
                            + "run this test through Maven/surefire or set "
                            + "-Dupgrade.current.version=<revision>");
        }
        return version;
    }

    @Override
    protected PublishedRelease.Coordinates releaseCoordinates() {
        return new PublishedRelease.Coordinates(
                "https://maven.pkg.github.com/eyupmiduck/dml_utils",
                "io.github.eyupmiduck", "dml_utils", currentVersion());
    }

    /**
     * The schemas the changelog manages, compared between the upgraded database
     * and a from-scratch build.
     *
     * @return the managed schemas
     */
    @Override
    protected Collection<String> managedSchemas() {
        return List.of("dml_utils", "dml_utils_lib", "dml_utils_data");
    }

    /**
     * Returns the upgrade configuration, which shares every value with the
     * regular test base except the master changelog.
     *
     * @return the configuration
     */
    @Override
    protected Configuration configuration() {
        return PostgresTestBase.UPGRADE_CONFIGURATION;
    }

    /**
     * The upgraded database has the dml_utils schemas, and the changed routines
     * have one signature each.
     */
    @Override
    protected void assertUpgradedDatabase() {
        assertEquals(3, upgradeDsl().fetchOne(
                                "SELECT count(*) FROM pg_namespace "
                                        + "WHERE nspname IN ('dml_utils', 'dml_utils_lib', 'dml_utils_data')")
                        .get(0, Integer.class),
                "the upgraded database should have the dml_utils schemas");

        // One input signature per changed routine: counting all 13 rows together
        // cannot prove that (they could be distributed unevenly).
        var signatures = upgradeDsl().fetch(
                "SELECT n.nspname, p.proname, count(*) AS signatures"
                        + " FROM pg_catalog.pg_proc AS p"
                        + " JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace"
                        + " WHERE (n.nspname = 'dml_utils'"
                        + "   AND p.proname IN ('run_migration_chunks', 'run_function_over_table',"
                        + "                        'set_migration_run_sleep_ms', 'pause_migration_run',"
                        + "                        'resume_migration_run', 'set_migration_run_retries',"
                        + "                        'migration_run_leaves', 'migration_run_progress',"
                        + "                        'explain_migration_run_chunk',"
                        + "                        'migration_run_summary', 'migration_boundaries'))"
                        + " OR (n.nspname = 'dml_utils_lib'"
                        + "   AND p.proname IN ('resolve_migration_run', 'populate_migration_boundaries'))"
                        + " GROUP BY n.nspname, p.proname");
        assertEquals(13, signatures.size(), "every changed routine is present after upgrade");
        for (org.jooq.Record signature : signatures) {
            assertEquals(1, signature.get("signatures", Integer.class),
                    () -> "the changed routine " + signature.get("nspname", String.class)
                            + "." + signature.get("proname", String.class)
                            + " must have one signature after upgrade");
        }
    }
}
