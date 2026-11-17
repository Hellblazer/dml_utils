package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the test database has ddl_utils installed alongside dml_utils, so
 * future dml_utils_data migrations can call the ddl_utils DDL helpers.
 */
class DdlUtilsInstallationTest extends PostgresTestBase {

    /**
     * The ddl_utils caller grants reach the dml_utils owner, and the helper can
     * alter a dml_utils fixture table.
     */
    @Test
    void installsDdlUtilsHelpersForTheDmlUtilsOwner() throws Exception {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("SELECT ddl_utils.add_column("
                    + "'dml_utils_fixtures', 'test_bigint', 'ddl_probe', 'text', true, NULL)");
            try {
                assertTrue(hasColumn("dml_utils_fixtures", "test_bigint", "ddl_probe"),
                        "the ddl_utils add_column helper alters a dml_utils fixture table");
            } finally {
                try {
                    statement.execute("ALTER TABLE dml_utils_fixtures.test_bigint"
                            + " DROP COLUMN IF EXISTS ddl_probe");
                } catch (SQLException cleanup) {
                    // Do not let a cleanup failure replace the assertion error
                    // being unwound; surface it on stderr for diagnosis.
                    cleanup.printStackTrace(System.err);
                }
            }
        }
    }

    /**
     * The ddl_utils install hands its Liquibase Database a connection the caller
     * still owns; closing that wrapper must not close the shared admin connection
     * (the upgrade hook uses it again after the install).
     */
    @Test
    void installingDdlUtilsDoesNotCloseTheSharedConnection() throws Exception {
        String database = dsl.fetchOne("SELECT current_database()").get(0, String.class);
        String scratch = "ddl_install_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + scratch);
            try {
                // Run the real install against a database that does not have the
                // changelog yet. Liquibase closes the Database it is given, and
                // the shared admin connection must survive that.
                try (Connection scratchAdmin = openAdminConnection(scratch)) {
                    PostgresTestBase.installDdlUtils(scratchAdmin);

                    assertFalse(scratchAdmin.isClosed(),
                            "the caller-owned connection stays open");
                    try (Statement probe = scratchAdmin.createStatement()) {
                        probe.execute("SELECT 1");
                    }
                }
            } finally {
                try {
                    statement.execute("DROP DATABASE IF EXISTS " + scratch + " WITH (FORCE)");
                } catch (SQLException cleanup) {
                    // Do not let a cleanup failure replace the install error
                    // being unwound; surface it on stderr for diagnosis.
                    cleanup.printStackTrace(System.err);
                }
            }
        }
    }
}
