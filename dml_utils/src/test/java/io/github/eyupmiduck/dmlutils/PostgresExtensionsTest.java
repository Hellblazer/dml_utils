package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ExtensionAssertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the extensions compiled into the custom PostgreSQL image are
 * available and installed in every test database: {@code plpgsql_check} for
 * static analysis and {@code pg_background} for the chunking routines. The
 * availability and installation checks delegate to the shared
 * {@link ExtensionAssertions}; the {@code pg_background} API probe is
 * dml_utils-specific.
 */
class PostgresExtensionsTest extends PostgresTestBase {

    private static final List<String> EXTENSIONS = List.of("plpgsql_check", "pg_background");

    /**
     * Both extensions are available and installed.
     */
    @Test
    void extensionsAreAvailableAndInstalled() throws Exception {
        try (Connection connection = openTestConnection()) {
            ExtensionAssertions.assertExtensionsAvailable(connection, EXTENSIONS);
            ExtensionAssertions.assertExtensionsInstalled(connection, EXTENSIONS);
        }
    }

    /**
     * {@code pg_background} exposes a working SQL API: a run completes without
     * error in the test database.
     */
    @Test
    void pgBackgroundRunWorks() {
        org.jooq.Record row = dsl.fetchOne(
                "SELECT completed, has_error, sqlstate, row_count, command_tag"
                        + " FROM pg_background_run('SELECT 42')");
        assertNotNull(row, "pg_background_run should return a row");

        assertEquals(Boolean.TRUE, row.get("completed", Boolean.class),
                "pg_background_run should complete");
        assertEquals(Boolean.FALSE, row.get("has_error", Boolean.class),
                "the statement runs without error");
        assertNull(row.get("sqlstate", String.class), "a successful statement has no SQLSTATE");
        assertEquals(1, row.get("row_count", Integer.class), "SELECT 42 returns one row");
        assertEquals("SELECT", row.get("command_tag", String.class),
                "the executed statement is the SELECT");
    }
}
