package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.BuildKeyRenderColumns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies {@code dml_utils_lib.build_key_render_columns}: key columns are
 * validated, qualified with the requested alias, and paired with the collation
 * to use for each text column (the schema-qualified column collation, or NULL
 * when the column is not in the catalog). Malformed keys are rejected with
 * {@code 22023}.
 */
class BuildKeyRenderColumnsTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";

    /**
     * The alias, key and collation cases.
     *
     * @return the render-column cases
     */
    private static java.util.stream.Stream<Arguments> renderColumnCases() {
        return java.util.stream.Stream.of(
                Arguments.of("non-text kind", "t", "test_bigint",
                        new String[]{"id"}, new String[]{"bigint"},
                        new String[]{"t.id"}, new String[]{null}),
                Arguments.of("mixed key", "t", "test_composite_mixed",
                        new String[]{"a", "b"}, new String[]{"bigint", "text"},
                        new String[]{"t.a", "t.b"},
                        new String[]{null, "pg_catalog.\"default\""}),
                Arguments.of("explicit collation", "t", "test_composite_collated",
                        new String[]{"a", "b"}, new String[]{"bigint", "text"},
                        new String[]{"t.a", "t.b"},
                        new String[]{null, "pg_catalog.\"en-x-icu\""}),
                Arguments.of("quoted alias and missing catalog column", "T odd",
                        "no_such_table", new String[]{"id"}, new String[]{"text"},
                        new String[]{"\"T odd\".id"}, new String[]{null}));
    }

    private BuildKeyRenderColumns columns(String alias, String table,
                                          String[] keyColumns, String[] kinds) {
        return Routines.buildKeyRenderColumns(dsl.configuration(), alias, SCHEMA, table,
                keyColumns, kinds);
    }

    /**
     * A repeated key column is rejected: it cannot be ordered consistently.
     */
    @Test
    void rejectsDuplicateKeyColumns() {
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{"id", "id"}, new String[]{"bigint", "bigint"}));
    }

    /**
     * The alias is baked into every column name, so a reserved substitution
     * sentinel in it is rejected.
     */
    @Test
    void rejectsASentinelInTheAlias() {
        assertSqlState("22023", () -> columns("t" + (char) 1, "test_bigint",
                new String[]{"id"}, new String[]{"bigint"}));
    }

    /**
     * Every key column is alias-qualified, a text column is paired with the
     * schema-qualified collation from the catalog (or a NULL collation when the
     * table/column is not in it), and an alias that needs quoting is quoted.
     *
     * @param description        the key shape
     * @param alias              the driving-table alias
     * @param table              the driving table
     * @param keyColumns         the key columns
     * @param kinds              the key kinds
     * @param expectedColumns    the expected qualified column names
     * @param expectedCollations the expected collations
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("renderColumnCases")
    void qualifiesColumnsAndResolvesCollations(String description, String alias, String table,
                                               String[] keyColumns, String[] kinds,
                                               String[] expectedColumns,
                                               String[] expectedCollations) {
        BuildKeyRenderColumns result = columns(alias, table, keyColumns, kinds);

        assertArrayEquals(expectedColumns, result.getOColumnNames());
        assertArrayEquals(expectedCollations, result.getOKeyCollations());
    }

    /**
     * Missing, empty and mismatched arrays are rejected, as are unsupported
     * kinds, NULL kinds and NULL/blank column names.
     */
    @Test
    void rejectsBadKeys() {
        assertSqlState("22023", () -> columns("t", "test_bigint", null,
                new String[]{"bigint"}));
        assertSqlState("22023", () -> columns("t", "test_bigint", new String[]{"id"},
                null));
        assertSqlState("22023", () -> columns("t", "test_bigint", new String[]{},
                new String[]{}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{"id", "id2"}, new String[]{"bigint"}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{"id"}, new String[]{"integer"}));
        assertSqlState("23514", () -> columns("t", "test_bigint",
                new String[]{"id"}, new String[]{null}));
        assertSqlState("23514", () -> columns("t", "test_bigint",
                new String[]{null}, new String[]{"bigint"}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{"id\u0001"}, new String[]{"bigint"}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{"id\u0002"}, new String[]{"bigint"}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{""}, new String[]{"bigint"}));
        // A whitespace-only name is blank, not just empty: it would render a
        // degenerate quoted identifier and only fail in a worker.
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{" \t"}, new String[]{"bigint"}));
    }

    /**
     * Arrays that are not one-dimensional or not one-based are rejected.
     */
    @Test
    void rejectsMalformedArrays() {
        assertSqlState("23514", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.build_key_render_columns("
                        + "'t', '" + SCHEMA + "', 'test_bigint',"
                        + " ARRAY[['id']]::name[], '{bigint}'::text[])"));
        assertSqlState("23514", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.build_key_render_columns("
                        + "'t', '" + SCHEMA + "', 'test_bigint',"
                        + " '[0:0]={id}'::name[], '{bigint}'::text[])"));
    }
}
