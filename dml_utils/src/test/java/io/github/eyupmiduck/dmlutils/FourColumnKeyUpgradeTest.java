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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that changesets {@code 011-allow-four-column-ordered-key} and
 * {@code 012-validate-four-column-ordered-key-check} apply on an installed
 * database: one that has already run every earlier changeset, including 009 and
 * 010, and so holds the ordered-key shape check that allows three columns. The
 * state is reached by applying the whole changelog, then putting back the old
 * check and forgetting 011 and 012, which is the state an install of the
 * previous revision is in. {@link LiquibaseUpgradeTest} covers the upgrade from
 * the last published release.
 */
class FourColumnKeyUpgradeTest extends PostgresTestBase {

    private static final String CHECK_CONSTRAINT = "migration_run_ordered_key_shape_check";

    private static final String THREE_COLUMN_CHECK =
            "ordered_key_columns IS NULL"
                    + " OR (pg_catalog.cardinality(ordered_key_columns) BETWEEN 1 AND 3"
                    + " AND pg_catalog.cardinality(ordered_key_kinds)"
                    + "     = pg_catalog.cardinality(ordered_key_columns)"
                    + " AND ordered_key_kinds <@ ARRAY ['bigint', 'text', 'uuid', 'timestamptz', 'bytea']::text[])";

    /**
     * On a database that holds the three-column check, a four-column ordered key
     * is refused; after the changelog is applied again, 011 and 012 run, the new
     * check is validated and admits four columns but not five, and no other
     * changeset runs a second time.
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

                assertEquals(1, count(owner, checkDefinitionContains("<= 3")),
                        "the installed check allows three columns");
                SQLException refused = assertThrows(SQLException.class,
                        () -> insertRun(owner, 4, "installed-four"));
                assertEquals("23514", refused.getSQLState(), "check_violation: " + refused.getMessage());

                applyChangelog(owner);

                assertEquals(2, count(owner, "SELECT count(*) FROM " + tracking() + " WHERE id IN"
                                + " ('011-allow-four-column-ordered-key',"
                                + "  '012-validate-four-column-ordered-key-check')"),
                        "011 and 012 are recorded");
                assertEquals(changesetsBefore, count(owner, "SELECT count(*) FROM " + tracking()),
                        "the second application records exactly the changesets the first one did");
                assertEquals(1, count(owner, "SELECT count(*) FROM pg_catalog.pg_constraint"
                                + " WHERE conname = '" + CHECK_CONSTRAINT + "' AND convalidated"),
                        "the new check is validated");
                assertEquals(1, count(owner, checkDefinitionContains("<= 4")),
                        "the new check allows four columns");

                insertRun(owner, 4, "upgraded-four");
                SQLException five = assertThrows(SQLException.class,
                        () -> insertRun(owner, 5, "upgraded-five"));
                assertEquals("23514", five.getSQLState(), "five columns stay refused: " + five.getMessage());
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static String tracking() {
        return LIQUIBASE_SCHEMA + "." + DATABASE_CHANGELOG_TABLE;
    }

    private static String checkDefinitionContains(String text) {
        return "SELECT count(*) FROM pg_catalog.pg_constraint WHERE conname = '" + CHECK_CONSTRAINT
                + "' AND conrelid = 'dml_utils_data.migration_run'::regclass"
                + " AND pg_catalog.pg_get_constraintdef(oid) LIKE '%" + text + "%'";
    }

    /**
     * Puts the database back in the state before 011: the three-column check, and
     * no record that 011 and 012 ran.
     */
    private static void restoreThreeColumnState(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            statement.execute("ALTER TABLE dml_utils_data.migration_run DROP CONSTRAINT " + CHECK_CONSTRAINT);
            statement.execute("ALTER TABLE dml_utils_data.migration_run ADD CONSTRAINT " + CHECK_CONSTRAINT
                    + " CHECK (" + THREE_COLUMN_CHECK + ")");
            statement.execute("DELETE FROM " + tracking() + " WHERE id IN"
                    + " ('011-allow-four-column-ordered-key',"
                    + "  '012-validate-four-column-ordered-key-check')");
        }
    }

    /**
     * Inserts an ordered-key run whose key has the given number of text columns,
     * in its own statement so a refusal leaves no partial state.
     */
    private static void insertRun(Connection owner, int columns, String label) throws SQLException {
        StringBuilder names = new StringBuilder();
        StringBuilder kinds = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
            names.append(i > 1 ? "," : "").append("'k").append(i).append("'");
            kinds.append(i > 1 ? "," : "").append("'text'");
        }
        try (Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size,"
                    + " chunk_by, threads, driving_table_schema_name, driving_table_name,"
                    + " ordered_key_index, ordered_key_columns, ordered_key_kinds)"
                    + " VALUES ('" + label + "', 'SELECT 1', 1, 'ordered_key', 1, 'public', 'src', 'src_idx',"
                    + " ARRAY[" + names + "]::name[], ARRAY[" + kinds + "]::text[])");
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

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
