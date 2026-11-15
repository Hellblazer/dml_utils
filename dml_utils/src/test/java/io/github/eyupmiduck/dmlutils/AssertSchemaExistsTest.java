package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Verifies {@code dml_utils_lib.assert_schema_exists}: it returns normally for an
 * existing schema (including one whose name was truncated to the 63-byte
 * identifier limit) and raises invalid_schema_name ({@code 3F000}) for a missing
 * one.
 */
class AssertSchemaExistsTest extends PostgresTestBase {

    /**
     * An existing schema is accepted without error.
     */
    @Test
    void acceptsAnExistingSchema() {
        Routines.assertSchemaExists(dsl.configuration(), "dml_utils_fixtures");
    }

    /**
     * A schema created with a name longer than 63 bytes is stored truncated; the
     * assertion must resolve it the same way, not report it missing.
     */
    @Test
    void acceptsASchemaNameLongerThanTheIdentifierLimit() throws Exception {
        String longName = "long_" + "x".repeat(80);
        String stored = longName.substring(0, 63);
        String database = dsl.fetchOne("SELECT current_database()").get(0, String.class);
        try (Connection owner = openAdminConnection(database);
             Statement statement = owner.createStatement()) {
            // Let PostgreSQL truncate the identifier, as it does for any DDL,
            // so the assertion depends on the routine truncating the same way.
            statement.execute("CREATE SCHEMA \"" + longName + "\"");
            try {
                Routines.assertSchemaExists(dsl.configuration(), longName);
            } finally {
                statement.execute("DROP SCHEMA " + stored);
            }
        }
    }

    /**
     * A missing schema raises {@code 3F000}.
     */
    @Test
    void rejectsAMissingSchema() {
        assertSqlState("3F000", () -> Routines.assertSchemaExists(dsl.configuration(), "no_such_schema"));
    }
}
