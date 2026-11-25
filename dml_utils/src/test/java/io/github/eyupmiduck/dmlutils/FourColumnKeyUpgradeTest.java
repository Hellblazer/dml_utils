package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that changesets {@code 012-allow-four-column-keys},
 * {@code 013-validate-four-column-ordered-key-check} and
 * {@code 014-validate-four-column-primary-key-check} apply on an installed
 * database: one that has already run every earlier changeset, including 009 to
 * 011, and so holds the primary-key and ordered-key shape checks that allow three
 * columns. The state is reached by applying the whole changelog, then putting
 * back the old checks and forgetting 012 to 014, which is the state an install of
 * the previous revision is in. {@link BaselineUpgradeTest} covers the upgrade
 * from the last published release.
 */
class FourColumnKeyUpgradeTest extends PostgresTestBase {

    private static final String ORDERED_CHECK = "migration_run_ordered_key_shape_check";
    private static final String PRIMARY_CHECK = "migration_run_primary_key_shape_check";

    private static final List<String> FOUR_COLUMN_CHANGESETS = List.of(
            "012-allow-four-column-keys",
            "013-validate-four-column-ordered-key-check",
            "014-validate-four-column-primary-key-check");

    private static String threeColumnCheck(String prefix) {
        return prefix + "_columns IS NULL"
                + " OR (pg_catalog.cardinality(" + prefix + "_columns) BETWEEN 1 AND 3"
                + " AND pg_catalog.cardinality(" + prefix + "_kinds)"
                + "     = pg_catalog.cardinality(" + prefix + "_columns)"
                + " AND " + prefix + "_kinds <@ ARRAY ['bigint', 'text', 'uuid', 'timestamptz', 'bytea']::text[])";
    }

    /**
     * On a database that holds the three-column checks, a four-column primary key
     * and ordered key are refused; after the changelog is applied again, 012 to
     * 014 run, the new checks are validated and admit four columns but not five,
     * and no other changeset runs a second time.
     */
    @Test
    void appliesTheFourColumnChangesetsToAnInstalledDatabase() throws Exception {
        String database = "four_col_upgrade_" + UUID.randomUUID().toString().replace('-', '_');
        createDatabase(database);
        try {
            prepare(database);
            try (Connection owner = openOwnerConnection(database)) {
                applyChangelog(owner);
                long changesetsBefore = count(owner, "SELECT count(*) FROM " + tracking());
                restoreThreeColumnState(owner);

                for (String check : List.of(ORDERED_CHECK, PRIMARY_CHECK)) {
                    assertEquals(1, count(owner, checkDefinitionContains(check, "<= 3")),
                            "the installed " + check + " allows three columns");
                }
                for (boolean primary : new boolean[]{false, true}) {
                    SQLException refused = assertThrows(SQLException.class,
                            () -> insertRun(owner, 4, "installed-four-" + primary, primary));
                    assertEquals("23514", refused.getSQLState(),
                            "check_violation: " + refused.getMessage());
                }

                applyChangelog(owner);

                assertEquals(3, count(owner, "SELECT count(*) FROM " + tracking() + " WHERE id IN "
                                + changesetIds()),
                        "012 to 014 are recorded");
                assertEquals(changesetsBefore, count(owner, "SELECT count(*) FROM " + tracking()),
                        "the second application records exactly the changesets the first one did");
                for (String check : List.of(ORDERED_CHECK, PRIMARY_CHECK)) {
                    assertEquals(1, count(owner, "SELECT count(*) FROM pg_catalog.pg_constraint"
                                    + " WHERE conname = '" + check + "' AND convalidated"),
                            "the new " + check + " is validated");
                    assertEquals(1, count(owner, checkDefinitionContains(check, "<= 4")),
                            "the new " + check + " allows four columns");
                }

                for (boolean primary : new boolean[]{false, true}) {
                    insertRun(owner, 4, "upgraded-four-" + primary, primary);
                    SQLException five = assertThrows(SQLException.class,
                            () -> insertRun(owner, 5, "upgraded-five-" + primary, primary));
                    assertEquals("23514", five.getSQLState(),
                            "five columns stay refused: " + five.getMessage());
                }
            }
        } finally {
            dropDatabase(database);
        }
    }

    /**
     * A database that has applied 012 but not yet 013 and 014 holds the new checks
     * NOT VALID: they are already enforced for new rows (four columns pass, five
     * are refused) and a run row recorded in that window validates when 013 and
     * 014 run.
     */
    @Test
    void validatesARowRecordedBetweenTheChangesets() throws Exception {
        String database = "four_col_window_" + UUID.randomUUID().toString().replace('-', '_');
        createDatabase(database);
        try {
            prepare(database);
            try (Connection owner = openOwnerConnection(database)) {
                applyChangelog(owner);
                restoreThreeColumnState(owner);

                applyNextChangesets(owner, 1);

                assertEquals(1, count(owner, "SELECT count(*) FROM " + tracking()
                                + " WHERE id = '012-allow-four-column-keys'"),
                        "012 is recorded");
                assertEquals(0, count(owner, "SELECT count(*) FROM " + tracking()
                                + " WHERE id IN ('013-validate-four-column-ordered-key-check',"
                                + " '014-validate-four-column-primary-key-check')"),
                        "013 and 014 have not run");
                for (String check : List.of(ORDERED_CHECK, PRIMARY_CHECK)) {
                    assertEquals(1, count(owner, "SELECT count(*) FROM pg_catalog.pg_constraint"
                                    + " WHERE conname = '" + check + "' AND NOT convalidated"),
                            "the new " + check + " is NOT VALID until its validating changeset");
                    assertEquals(1, count(owner, checkDefinitionContains(check, "<= 4")),
                            "the new " + check + " allows four columns");
                }
                for (boolean primary : new boolean[]{false, true}) {
                    SQLException five = assertThrows(SQLException.class,
                            () -> insertRun(owner, 5, "window-five-" + primary, primary));
                    assertEquals("23514", five.getSQLState(),
                            "five columns are refused while the check is NOT VALID: " + five.getMessage());
                    insertRun(owner, 3, "window-three-" + primary, primary);
                }

                applyChangelog(owner);

                assertEquals(2, count(owner, "SELECT count(*) FROM " + tracking()
                                + " WHERE id IN ('013-validate-four-column-ordered-key-check',"
                                + " '014-validate-four-column-primary-key-check')"),
                        "013 and 014 are recorded");
                for (String check : List.of(ORDERED_CHECK, PRIMARY_CHECK)) {
                    assertEquals(1, count(owner, "SELECT count(*) FROM pg_catalog.pg_constraint"
                                    + " WHERE conname = '" + check + "' AND convalidated"),
                            check + " validates with the three-column row in place");
                }
                assertEquals(2, count(owner, "SELECT count(*) FROM dml_utils_data.migration_run"
                                + " WHERE label LIKE 'window-three-%'"),
                        "the rows recorded in the window are kept");
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static String tracking() {
        return LIQUIBASE_SCHEMA + "." + DATABASE_CHANGELOG_TABLE;
    }

    private static String changesetIds() {
        return FOUR_COLUMN_CHANGESETS.stream()
                .map(id -> "'" + id + "'")
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    private static String checkDefinitionContains(String check, String text) {
        return "SELECT count(*) FROM pg_catalog.pg_constraint WHERE conname = '" + check
                + "' AND conrelid = 'dml_utils_data.migration_run'::regclass"
                + " AND pg_catalog.pg_get_constraintdef(oid) LIKE '%" + text + "%'";
    }

    /**
     * Puts the database back in the state before 012: the three-column checks, and
     * no record that 012 to 014 ran.
     */
    private static void restoreThreeColumnState(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            for (String[] check : new String[][]{{ORDERED_CHECK, "ordered_key"},
                    {PRIMARY_CHECK, "primary_key"}}) {
                statement.execute("ALTER TABLE dml_utils_data.migration_run DROP CONSTRAINT " + check[0]);
                statement.execute("ALTER TABLE dml_utils_data.migration_run ADD CONSTRAINT " + check[0]
                        + " CHECK (" + threeColumnCheck(check[1]) + ")");
            }
            statement.execute("DELETE FROM " + tracking() + " WHERE id IN " + changesetIds());
        }
    }

    /**
     * Inserts a run whose key has the given number of text columns, in its own
     * statement so a refusal leaves no partial state.
     *
     * @param primary {@code true} for a primary-key run, {@code false} for an
     *                ordered-key run
     */
    private static void insertRun(Connection owner, int columns, String label, boolean primary)
            throws SQLException {
        StringBuilder names = new StringBuilder();
        StringBuilder kinds = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
            names.append(i > 1 ? "," : "").append("'k").append(i).append("'");
            kinds.append(i > 1 ? "," : "").append("'text'");
        }
        String strategy = primary ? "primary_key" : "ordered_key";
        String keyColumns = primary
                ? "primary_key_columns, primary_key_kinds"
                : "ordered_key_index, ordered_key_columns, ordered_key_kinds";
        String keyValues = (primary ? "" : "'src_idx', ")
                + "ARRAY[" + names + "]::name[], ARRAY[" + kinds + "]::text[]";
        try (Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size,"
                    + " chunk_by, threads, driving_table_schema_name, driving_table_name,"
                    + " " + keyColumns + ")"
                    + " VALUES ('" + label + "', 'SELECT 1', 1, '" + strategy + "', 1, 'public', 'src',"
                    + " " + keyValues + ")");
        }
    }

    /**
     * Prepares a scratch database the way the upgrade test does: the extensions,
     * the staged ddl_utils changelog and a Liquibase schema the owner can use.
     */
    private void prepare(String database) throws SQLException {
        // The ddl_utils install closes the connection it is given, so the grants
        // use a second one.
        try (Connection admin = openAdminConnection(database)) {
            installProjectExtensionsAndDdlUtils(admin);
        }
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("GRANT CREATE ON DATABASE \"" + database + "\" TO " + OWNER_USER);
            statement.execute("GRANT CREATE ON SCHEMA public TO " + OWNER_USER);
        }
    }

    private static void applyChangelog(Connection owner) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(owner));
        database.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
        database.setDatabaseChangeLogTableName(DATABASE_CHANGELOG_TABLE);
        database.setDatabaseChangeLogLockTableName(DATABASE_CHANGELOG_LOCK_TABLE);
        new Liquibase(ChangelogTestSupport.MASTER_RESOURCE, new ClassLoaderResourceAccessor(), database)
                .update();
        // Liquibase leaves the connection in manual-commit mode.
        owner.setAutoCommit(true);
    }

    /**
     * Applies only the next {@code count} changesets that have not run yet.
     */
    private static void applyNextChangesets(Connection owner, int count) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(owner));
        database.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
        database.setDatabaseChangeLogTableName(DATABASE_CHANGELOG_TABLE);
        database.setDatabaseChangeLogLockTableName(DATABASE_CHANGELOG_LOCK_TABLE);
        new Liquibase(ChangelogTestSupport.MASTER_RESOURCE, new ClassLoaderResourceAccessor(), database)
                .update(count, "");
        owner.setAutoCommit(true);
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
