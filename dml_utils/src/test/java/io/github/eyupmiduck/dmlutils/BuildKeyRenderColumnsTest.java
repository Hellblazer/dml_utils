package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.BuildKeyRenderColumns;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies {@code dml_utils_lib.build_key_render_columns}: key columns are
 * validated, qualified with the requested alias, and paired with the collation
 * to use for each text column (the quoted column collation, 'default', or NULL
 * when the column is not in the catalog). Malformed keys are rejected with
 * {@code 22023}.
 */
class BuildKeyRenderColumnsTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";

    private BuildKeyRenderColumns columns(String alias, String table,
                                          String[] keyColumns, String[] kinds) {
        return Routines.buildKeyRenderColumns(dsl.configuration(), alias, SCHEMA, table,
                keyColumns, kinds);
    }

    /**
     * Non-text kinds get NULL collations and every column is alias-qualified.
     */
    @Test
    void qualifiesColumnsAndLeavesNonTextCollationsNull() {
        BuildKeyRenderColumns result = columns("t", "test_bigint",
                new String[]{"id"}, new String[]{"bigint"});

        assertArrayEquals(new String[]{"t.id"}, result.getOColumnNames());
        assertArrayEquals(new String[]{null}, result.getOKeyCollations());
    }

    /**
     * A mixed key qualifies both columns and resolves the text column's
     * collation.
     */
    @Test
    void resolvesAMixedKey() {
        BuildKeyRenderColumns result = columns("t", "test_composite_mixed",
                new String[]{"a", "b"}, new String[]{"bigint", "text"});

        assertArrayEquals(new String[]{"t.a", "t.b"}, result.getOColumnNames());
        assertArrayEquals(new String[]{null, "\"default\""}, result.getOKeyCollations());
    }

    /**
     * An explicitly collated text column keeps its quoted collation name.
     */
    @Test
    void resolvesAnExplicitCollation() {
        BuildKeyRenderColumns result = columns("t", "test_composite_collated",
                new String[]{"a", "b"}, new String[]{"bigint", "text"});

        assertArrayEquals(new String[]{"t.a", "t.b"}, result.getOColumnNames());
        assertArrayEquals(new String[]{null, "\"en-x-icu\""}, result.getOKeyCollations());
    }

    /**
     * An alias that needs quoting is quoted, and a column outside the catalog
     * falls back to a NULL collation.
     */
    @Test
    void quotesTheAliasAndToleratesAMissingCatalogColumn() {
        BuildKeyRenderColumns result = columns("T odd", "no_such_table",
                new String[]{"id"}, new String[]{"text"});

        assertArrayEquals(new String[]{"\"T odd\".id"}, result.getOColumnNames());
        assertArrayEquals(new String[]{null}, result.getOKeyCollations());
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
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{"id"}, new String[]{null}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{null}, new String[]{"bigint"}));
        assertSqlState("22023", () -> columns("t", "test_bigint",
                new String[]{""}, new String[]{"bigint"}));
    }

    /**
     * Arrays that are not one-dimensional or not one-based are rejected.
     */
    @Test
    void rejectsMalformedArrays() {
        assertSqlState("22023", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.build_key_render_columns("
                        + "'t', '" + SCHEMA + "', 'test_bigint',"
                        + " ARRAY[['id']]::name[], '{bigint}'::text[])"));
        assertSqlState("22023", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.build_key_render_columns("
                        + "'t', '" + SCHEMA + "', 'test_bigint',"
                        + " '[0:0]={id}'::name[], '{bigint}'::text[])"));
    }
}
