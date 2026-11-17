package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

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
        try (Connection admin = openOwnerConnection()) {
            Connection wrapper = PostgresTestBase.closeIgnoring(admin);
            wrapper.close();

            assertFalse(admin.isClosed(), "the caller-owned connection stays open");
            try (Statement statement = admin.createStatement()) {
                statement.execute("SELECT 1");
            }
        }
    }
}
