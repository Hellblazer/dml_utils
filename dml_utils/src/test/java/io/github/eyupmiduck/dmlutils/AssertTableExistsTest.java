package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Verifies {@code dml_utils_lib.assert_table_exists}: it returns normally for an
 * existing plain table, raises undefined_table ({@code 42P01}) for a missing
 * relation and wrong_object_type ({@code 42809}) for one that is not a plain or
 * partitioned table.
 */
class AssertTableExistsTest extends PostgresTestBase {

    /**
     * An existing table is accepted without error.
     */
    @Test
    void acceptsAnExistingTable() {
        Routines.assertTableExists(dsl.configuration(), "dml_utils_fixtures", "test_bigint");
    }

    /**
     * A partitioned parent is accepted: partition-wise chunking depends on it.
     */
    @Test
    void acceptsAPartitionedTable() {
        Routines.assertTableExists(dsl.configuration(), "dml_utils_fixtures",
                "test_partitioned");
    }

    /**
     * A view is reported as the wrong object type, not as a missing table.
     */
    @Test
    void rejectsARelationThatIsNotATable() throws Exception {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE VIEW dml_utils_fixtures.assert_table_exists_probe AS"
                    + " SELECT 1 AS id");
            try {
                assertSqlState("42809", () -> Routines.assertTableExists(
                        dsl.configuration(), "dml_utils_fixtures",
                        "assert_table_exists_probe"));
            } finally {
                statement.execute("DROP VIEW dml_utils_fixtures.assert_table_exists_probe");
            }
        }
    }

    /**
     * A missing table raises {@code 42P01}.
     */
    @Test
    void rejectsAMissingTable() {
        assertSqlState("42P01", () -> Routines.assertTableExists(
                dsl.configuration(), "dml_utils_fixtures", "no_such_table"));
    }
}
