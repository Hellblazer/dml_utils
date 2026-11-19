package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the primary-key helpers share one reader and one arity contract:
 * {@code primary_key_columns} and {@code primary_key_kinds} delegate to
 * {@code primary_key_attributes}, and a column count that disagrees with the
 * key's declared arity is reported by {@code assert_primary_key_arity_matches}
 * as a catalog inconsistency.
 */
class PrimaryKeyHelpersTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";

    /**
     * The columns and kinds arrays line up for a three-column key in key order.
     */
    @Test
    void columnsAndKindsAlignWithTheKey() {
        assertArrayEquals(new String[]{"b", "a", "c"},
                Routines.primaryKeyColumns(dsl.configuration(), FIXTURE_SCHEMA,
                        "test_composite_three"),
                "the columns are in index order, not physical order");
        assertArrayEquals(new String[]{"bigint", "text", "uuid"},
                Routines.primaryKeyKinds(dsl.configuration(), FIXTURE_SCHEMA,
                        "test_composite_three"),
                "one kind per column, in key order");
    }

    /**
     * Both helpers take the unsupported-key rejection from the one reader, so a
     * table without a primary key or with more than three key columns is
     * {@code 22023} for either of them.
     */
    @Test
    void delegatesTheUnsupportedKeyRejectionToOneReader() {
        for (String table : List.of("test_no_pk", "test_composite_four")) {
            assertSqlState("22023", () -> Routines.primaryKeyColumns(
                    dsl.configuration(), FIXTURE_SCHEMA, table));
            assertSqlState("22023", () -> Routines.primaryKeyKinds(
                    dsl.configuration(), FIXTURE_SCHEMA, table));
        }
    }

    /**
     * A count that matches the declared arity passes.
     */
    @Test
    void arityCheckAcceptsTheDeclaredCount() {
        assertDoesNotThrow(() -> Routines.assertPrimaryKeyArityMatches(
                dsl.configuration(), FIXTURE_SCHEMA, "test_composite_three", 3));
    }

    /**
     * A count that disagrees with the declared arity is a catalog inconsistency
     * ({@code 55000}), not a caller argument error, and says so.
     */
    @Test
    void arityCheckRejectsAMismatchAsACatalogInconsistency() {
        DataAccessException error = assertThrows(DataAccessException.class,
                () -> Routines.assertPrimaryKeyArityMatches(
                        dsl.configuration(), FIXTURE_SCHEMA, "test_composite_three", 2));

        assertEquals("55000", error.sqlState(), "one state for the inconsistency");
        assertTrue(error.getMessage().contains("inconsistent in the catalog"),
                () -> "the message must not claim a concurrent change: " + error.getMessage());
    }

    /**
     * A keyless table is rejected by the same check with {@code 22023}, matching
     * the reader's contract.
     */
    @Test
    void arityCheckRejectsAKeylessTable() {
        assertSqlState("22023", () -> Routines.assertPrimaryKeyArityMatches(
                dsl.configuration(), FIXTURE_SCHEMA, "test_no_pk", 1));
    }

    /**
     * An unsupported key-column type is still {@code 22023} for the kinds helper.
     */
    @Test
    void kindsRejectAnUnsupportedType() {
        assertSqlState("22023", () -> Routines.primaryKeyKinds(
                dsl.configuration(), FIXTURE_SCHEMA, "test_numeric"));
    }
}
