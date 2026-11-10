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
 * Verifies the precondition on changeset {@code 008-add-bytea-key-kind}: a
 * database without ddl_utils halts at 008 before anything changes, so the
 * {@code migration_key} type is never left with the bytea attribute while the
 * routines that build a key still construct the old, narrower row.
 */
class DdlUtilsPreconditionTest extends PostgresTestBase {

    private static final String KEY_ATTRIBUTES =
            "SELECT count(*) FROM pg_catalog.pg_attribute AS a"
                    + " WHERE a.attrelid = (SELECT typrelid FROM pg_catalog.pg_type"
                    + "                     WHERE oid = 'dml_utils_data.migration_key'::regtype)"
                    + " AND a.attnum > 0 AND NOT a.attisdropped";

    /**
     * Applying the changelog to a database that has no ddl_utils schema fails at
     * 008, leaves {@code migration_key} with its four original attributes and
     * records no later changeset, so the migration can be re-run once ddl_utils
     * is installed.
     */
    @Test
    void haltsBeforeChangingMigrationKeyWhenDdlUtilsIsMissing() throws Exception {
        String database = "no_ddl_utils_" + UUID.randomUUID().toString().replace('-', '_');
        createDatabase(database);
        try {
            prepare(database);
            try (Connection owner = openOwnerConnection(database)) {
                Throwable failure = assertThrows(Exception.class, () -> applyChangelog(owner));

                assertTrue(messages(failure).contains("008-add-bytea-key-kind"),
                        "the failure names the changeset that halted: " + messages(failure));
                assertEquals(4, count(owner, KEY_ATTRIBUTES),
                        "migration_key keeps its original attributes");
                assertEquals(1, count(owner, trackedChangeSets("004-create-domains")),
                        "the changesets before 008 ran");
                assertEquals(0, count(owner, trackedChangeSets("008-add-bytea-key-kind")),
                        "008 is not recorded as run");
                assertEquals(0, count(owner, trackedChangeSets("005-create-migration-key-check")),
                        "nothing after 008 ran");
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static String trackedChangeSets(String id) {
        return "SELECT count(*) FROM " + LIQUIBASE_SCHEMA + "." + DATABASE_CHANGELOG_TABLE
                + " WHERE id = '" + id + "'";
    }

    /**
     * Prepares a scratch database the way the upgrade test does, but without
     * installing ddl_utils.
     */
    private void prepare(String database) throws SQLException {
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            installProjectExtensions(admin);
            statement.execute("GRANT CREATE ON DATABASE \"" + database + "\" TO " + OWNER_USER);
            statement.execute("GRANT CREATE ON SCHEMA public TO " + OWNER_USER);
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + LIQUIBASE_SCHEMA
                    + " AUTHORIZATION " + OWNER_USER);
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
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append('\n');
        }
        return text.toString();
    }
}
