package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.testing.PublishedRelease;
import io.github.eyupmiduck.changelogvalidator.testing.UpgradeContract;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p>This runs against the v1.16.0 baseline, the first release cut from the
 * cleaned changelog. A release is a prerequisite for a meaningful upgrade test:
 * editing an existing changeset in place changes its Liquibase checksum, so the
 * test must be re-pointed only after the cleaned baseline is published.
 */
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
                            + "run this test through Maven/surefire or set -Dupgrade.current.version");
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

        var check = upgradeDsl().fetchOne("SELECT convalidated, pg_catalog.pg_get_constraintdef(oid) AS definition"
                + " FROM pg_catalog.pg_constraint"
                + " WHERE conname = 'migration_run_ordered_key_shape_check'"
                + " AND conrelid = 'dml_utils_data.migration_run'::regclass");
        assertTrue(check.get("convalidated", Boolean.class),
                "the upgraded ordered-key shape check is validated");
        assertTrue(check.get("definition", String.class).contains("'bytea'::text"),
                "the upgraded ordered-key shape check admits bytea");
        assertEquals(5, upgradeDsl().fetchOne(
                        "SELECT count(*) FROM pg_catalog.pg_attribute"
                                + " WHERE attrelid = (SELECT typrelid FROM pg_catalog.pg_type"
                                + " WHERE oid = 'dml_utils_data.migration_key'::regtype)"
                                + " AND attnum > 0 AND NOT attisdropped")
                        .get(0, Integer.class),
                "the upgraded migration_key has the bytea attribute");
    }

    /**
     * A partition-wise run that failed part way on the previous release, with its
     * leaf terminals stored in the four-attribute key, resumes after the upgrade:
     * the stored boundaries and terminals stay readable, {@code next_migration_chunk}
     * returns the failed chunk ending at the stored terminal, and finishing the
     * run processes every row exactly once.
     */
    @Test
    void resumesARunStoppedOnThePreviousRelease() throws Exception {
        Path previousJar = previousReleaseJar().orElseThrow();
        String database = "upgrade_resume_" + UUID.randomUUID().toString().replace('-', '_');
        createDatabase(database);
        try {
            installExtensions(database);
            try (Connection admin = openAdminConnection(database);
                 Statement statement = admin.createStatement()) {
                statement.execute("GRANT CREATE ON DATABASE \"" + database + "\" TO "
                        + PostgresTestBase.OWNER_USER);
                statement.execute("GRANT CREATE ON SCHEMA public TO " + PostgresTestBase.OWNER_USER);
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + PostgresTestBase.LIQUIBASE_SCHEMA
                        + " AUTHORIZATION " + PostgresTestBase.OWNER_USER);
            }

            try (Connection owner = openOwnerConnection(database)) {
                try (URLClassLoader previous = new URLClassLoader(
                        new URL[]{previousJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                    applyChangelog(owner, new ClassLoaderResourceAccessor(previous));
                }
                assertEquals(4, keyAttributes(owner), "the previous release has the four-array key");
                seedPartitionedSource(owner);

                String label = "upgrade-resume-" + UUID.randomUUID();
                try (Connection caller = openTestConnection(database);
                     Statement statement = caller.createStatement()) {
                    SQLException failure = assertThrows(
                            SQLException.class, () -> statement.execute(runSql(label,
                                    "UPDATE <driving_table> SET payload = CASE WHEN t.id = 3"
                                            + " THEN (t.id / (t.id - 3))::text ELSE 'x' END"
                                            + " WHERE <chunking_clause>")));
                    assertEquals("22012", failure.getSQLState(), "the first run fails on id 3: " + failure.getMessage());
                }
                assertEquals(1, scalar(owner, "SELECT count(*) FROM dml_utils_data.migration_boundary"
                        + " WHERE completed_at IS NOT NULL"), "one chunk completed before the failure");
                assertEquals(2, scalar(owner, "SELECT count(*) FROM dml_utils_data.migration_run_leaf"
                        + " WHERE (terminal_boundary_id).bigint_values IS NOT NULL"),
                        "both leaves stored a terminal key");
                long runId = scalar(owner, "SELECT run_id FROM dml_utils_data.migration_run"
                        + " WHERE label = '" + label + "'");

                applyChangelog(owner, new ClassLoaderResourceAccessor());
                assertEquals(5, keyAttributes(owner), "the upgrade added the bytea attribute");
                assertEquals(2, scalar(owner, "SELECT count(*) FROM dml_utils_data.migration_run_leaf"
                        + " WHERE (terminal_boundary_id).bigint_values IS NOT NULL"
                        + " AND (terminal_boundary_id).bytea_values IS NULL"),
                        "stored terminal keys still read, with no bytea array");

                try (Connection caller = openTestConnection(database);
                     Statement statement = caller.createStatement()) {
                    try (ResultSet next = statement.executeQuery(
                            "SELECT o_boundary_no, o_chunk_table_name, o_start_values::text,"
                                    + " o_end_values::text, o_is_final"
                                    + " FROM dml_utils_lib.next_migration_chunk("
                                    + runId + ", true, ARRAY['bigint'], ARRAY[]::bigint[], NULL, NULL)")) {
                        assertTrue(next.next(), "the failed chunk is next");
                        assertEquals(1L, next.getLong(1), "the failed chunk");
                        assertEquals("upg_src_a", next.getString(2), "it belongs to the first leaf");
                        assertEquals("{3}", next.getString(3), "it starts at id 3");
                        assertEquals("{3}", next.getString(4),
                                "it ends at the leaf terminal stored by the previous release");
                        assertTrue(next.getBoolean(5), "the last chunk of a leaf is inclusive");
                    }
                    statement.execute("SELECT dml_utils.set_migration_run_sql_text(" + quote(label) + ", "
                            + quote("UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x'"
                            + " WHERE <chunking_clause>") + ")");
                    statement.execute(runSql(label,
                            "UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x'"
                                    + " WHERE <chunking_clause>"));
                }
                assertEquals(6, scalar(owner, "SELECT count(*) FROM public.upg_src WHERE payload = 'x'"),
                        "every row is processed exactly once across the upgrade");
                assertEquals(1, scalar(owner, "SELECT count(*) FROM dml_utils_data.migration_run"
                        + " WHERE run_id = " + runId + " AND completed_at IS NOT NULL"
                        + " AND total_chunks_completed = total_chunks"), "the resumed run completes");
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static String runSql(String label, String template) {
        return "SELECT dml_utils.run_migration_chunks(" + quote(template) + ", 'public', 'upg_src', "
                + quote(label) + ", 2, 1, 't', 'primary_key', 1, 0, NULL, true)";
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static void applyChangelog(Connection owner, ResourceAccessor accessor) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(owner));
        database.setLiquibaseSchemaName(PostgresTestBase.LIQUIBASE_SCHEMA);
        database.setDatabaseChangeLogTableName(PostgresTestBase.DATABASE_CHANGELOG_TABLE);
        database.setDatabaseChangeLogLockTableName(PostgresTestBase.DATABASE_CHANGELOG_LOCK_TABLE);
        new Liquibase(ChangelogTestSupport.MASTER_RESOURCE, accessor, database).update();
        // Liquibase leaves the connection in manual-commit mode; the seeding and
        // the pg_background workers need what follows to be committed.
        owner.setAutoCommit(true);
    }

    private static void seedPartitionedSource(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            statement.execute("CREATE TABLE public.upg_src (id bigint NOT NULL, payload text,"
                    + " PRIMARY KEY (id)) PARTITION BY RANGE (id)");
            statement.execute("CREATE TABLE public.upg_src_a PARTITION OF public.upg_src"
                    + " FOR VALUES FROM (1) TO (4)");
            statement.execute("CREATE TABLE public.upg_src_b PARTITION OF public.upg_src"
                    + " FOR VALUES FROM (4) TO (7)");
            statement.execute("INSERT INTO public.upg_src (id) SELECT g FROM generate_series(1, 6) AS g");
            statement.execute("GRANT USAGE ON SCHEMA public TO dml_utils_caller");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.upg_src,"
                    + " public.upg_src_a, public.upg_src_b TO dml_utils_caller");
        }
    }

    private static long keyAttributes(Connection connection) throws SQLException {
        return scalar(connection, "SELECT count(*) FROM pg_catalog.pg_attribute"
                + " WHERE attrelid = (SELECT typrelid FROM pg_catalog.pg_type"
                + " WHERE oid = 'dml_utils_data.migration_key'::regtype)"
                + " AND attnum > 0 AND NOT attisdropped");
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
