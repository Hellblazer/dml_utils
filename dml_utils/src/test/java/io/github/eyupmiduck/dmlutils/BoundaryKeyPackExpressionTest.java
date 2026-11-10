package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.boundary_key_pack_expression}: each key kind is
 * placed in its position-aligned migration_key array, unpopulated arrays are
 * NULL, and malformed or unsupported kind arrays are rejected with {@code 22023}.
 */
class BoundaryKeyPackExpressionTest extends PostgresTestBase {

    private String pack(String[] kinds) {
        return Routines.boundaryKeyPackExpression(dsl.configuration(), kinds);
    }

    /**
     * A single bigint key populates only the bigint array.
     */
    @Test
    void packsASingleBigintKey() {
        assertEquals("ROW(ARRAY[id1::bigint]::bigint[], NULL::text[], NULL::uuid[],"
                        + " NULL::timestamptz[], NULL::bytea[])::dml_utils_data.migration_key",
                pack(new String[]{"bigint"}));
    }

    /**
     * A single text key populates only the text array.
     */
    @Test
    void packsASingleTextKey() {
        assertEquals("ROW(NULL::bigint[], ARRAY[id1::text]::text[], NULL::uuid[],"
                        + " NULL::timestamptz[], NULL::bytea[])::dml_utils_data.migration_key",
                pack(new String[]{"text"}));
    }

    /**
     * A mixed two-column key keeps each kind in its position, with NULL
     * placeholders for the other kinds.
     */
    @Test
    void packsAMixedTwoColumnKey() {
        assertEquals("ROW(ARRAY[NULL, id2::bigint]::bigint[],"
                        + " ARRAY[id1::text, NULL]::text[], NULL::uuid[],"
                        + " NULL::timestamptz[], NULL::bytea[])::dml_utils_data.migration_key",
                pack(new String[]{"text", "bigint"}));
    }

    /**
     * A three-column key places uuid, bigint and text terms in their own arrays.
     */
    @Test
    void packsAThreeColumnKey() {
        assertEquals("ROW(ARRAY[NULL, id2::bigint, NULL]::bigint[],"
                        + " ARRAY[NULL, NULL, id3::text]::text[],"
                        + " ARRAY[id1::uuid, NULL, NULL]::uuid[],"
                        + " NULL::timestamptz[], NULL::bytea[])::dml_utils_data.migration_key",
                pack(new String[]{"uuid", "bigint", "text"}));
    }

    /**
     * A bytea key populates only the bytea array, and a composite
     * (text, text, bytea) key keeps the bytea term at its own position.
     */
    @Test
    void packsByteaKeys() {
        assertEquals("ROW(NULL::bigint[], NULL::text[], NULL::uuid[],"
                        + " NULL::timestamptz[], ARRAY[id1::bytea]::bytea[])"
                        + "::dml_utils_data.migration_key",
                pack(new String[]{"bytea"}));
        assertEquals("ROW(NULL::bigint[], ARRAY[id1::text, id2::text, NULL]::text[],"
                        + " NULL::uuid[], NULL::timestamptz[],"
                        + " ARRAY[NULL, NULL, id3::bytea]::bytea[])"
                        + "::dml_utils_data.migration_key",
                pack(new String[]{"text", "text", "bytea"}));
    }

    /**
     * A NULL/empty/oversized kind array, a NULL kind and an unsupported kind are
     * rejected.
     */
    @Test
    void rejectsBadKindArrays() {
        assertSqlState("22023", () -> pack(null));
        assertSqlState("22023", () -> pack(new String[]{}));
        assertSqlState("22023", () -> pack(new String[]{"bigint", "text", "uuid", "text"}));
        assertSqlState("22023", () -> pack(new String[]{null}));
        assertSqlState("22023", () -> pack(new String[]{"integer"}));
        assertSqlState("22023", () -> pack(new String[]{"BIGINT"}));
    }

    /**
     * Non-1-D and non-1-based arrays are rejected through raw SQL.
     */
    @Test
    void rejectsMalformedArrays() {
        assertSqlState("22023", () -> dsl.fetch(
                "SELECT dml_utils_lib.boundary_key_pack_expression(ARRAY[['bigint']]::text[])"));
        assertSqlState("22023", () -> dsl.fetch(
                "SELECT dml_utils_lib.boundary_key_pack_expression('[0:0]={bigint}'::text[])"));
    }
}
