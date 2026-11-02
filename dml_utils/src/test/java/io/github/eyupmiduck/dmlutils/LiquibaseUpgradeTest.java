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
 *
 * <p>Suspended while the pre-deployment baseline is edited in place: changing an
 * existing changeset changes its Liquibase checksum, so upgrading the last
 * published release is not meaningful until a release is cut from the cleaned
 * changelog. Restore this test in dml-b87.13 once that baseline exists and the
 * partition-wise epic (dml-sw6) is complete.
 */
@Disabled("Pre-deployment baseline edit: changesets are being changed in place; restore in dml-b87.13 after dml-sw6")
class LiquibaseUpgradeTest extends UpgradeContract {

    @Override
    protected PublishedRelease.Coordinates releaseCoordinates() {
        return new PublishedRelease.Coordinates(
                "https://maven.pkg.github.com/eyupmiduck/dml_utils",
                "io.github.eyupmiduck", "dml_utils",
                System.getProperty("upgrade.current.version", "0.0.0-SNAPSHOT"));
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

        assertEquals(5, upgradeDsl().fetchOne(
                                "SELECT count(*) FROM pg_catalog.pg_proc AS p"
                                        + " JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace"
                                        + " WHERE (n.nspname = 'dml_utils'"
                                        + "   AND p.proname IN ('run_migration_chunks', 'run_function_over_table',"
                                        + "                        'set_migration_run_sleep_ms'))"
                                        + " OR (n.nspname = 'dml_utils_lib'"
                                        + "   AND p.proname IN ('resolve_migration_run', 'populate_migration_boundaries'))")
                        .get(0, Integer.class),
                "the changed routines should have one signature each after upgrade");
    }
}
