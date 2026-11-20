package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.BoundaryKeyDescriptorSqlRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.boundary_key_descriptor_sql}: a one-to-three
 * column boundary key is rendered as aliased {@code idN} select terms, matching
 * id and order lists, a fully descending order list and the
 * {@code migration_key} pack expression; malformed arrays and unsupported kinds
 * are rejected with {@code 22023}.
 */
class BoundaryKeyDescriptorSqlTest extends PostgresTestBase {

    private BoundaryKeyDescriptorSqlRecord describe(String[] columns, String[] kinds) {
        return Routines.boundaryKeyDescriptorSql(dsl.configuration(), columns, kinds).getFirst();
    }

    /**
     * A single bigint key becomes one alias, one order term and a pack expression
     * with only the bigint array populated.
     */
    @Test
    void describesASingleBigintKey() {
        BoundaryKeyDescriptorSqlRecord row = describe(new String[]{"k1"},
                new String[]{"bigint"});

        assertEquals("k1 AS id1", row.getOSelectList());
        assertEquals("id1", row.getOIdList());
        assertEquals("k1", row.getOOrderBy());
        assertEquals("id1 DESC", row.getOOrderByDesc());
        assertEquals("ROW(ARRAY[id1::bigint]::bigint[], NULL::text[], NULL::uuid[],"
                        + " NULL::timestamptz[])::dml_utils_data.migration_key",
                row.getOPackExpression());
    }

    /**
     * A composite (timestamptz, bigint) key renders both aliases and order terms,
     * and keeps each kind in its position of the position-aligned pack arrays.
     */
    @Test
    void describesACompositeTimestamptzBigintKey() {
        BoundaryKeyDescriptorSqlRecord row = describe(new String[]{"created_at", "id"},
                new String[]{"timestamptz", "bigint"});

        assertEquals("created_at AS id1, id AS id2", row.getOSelectList());
        assertEquals("id1, id2", row.getOIdList());
        assertEquals("created_at, id", row.getOOrderBy());
        assertEquals("id1 DESC, id2 DESC", row.getOOrderByDesc());
        assertEquals("ROW(ARRAY[NULL, id2::bigint]::bigint[], NULL::text[],"
                + " NULL::uuid[], ARRAY[id1::timestamptz, NULL]::timestamptz[])"
                + "::dml_utils_data.migration_key", row.getOPackExpression());
    }

    /**
     * A three-column key with the three remaining kinds keeps every position and
     * escapes an identifier that needs quoting.
     */
    @Test
    void describesAThreeColumnMixedKeyAndQuotesIdentifiers() {
        BoundaryKeyDescriptorSqlRecord row = describe(
                new String[]{"external_id", "select", "label"},
                new String[]{"uuid", "bigint", "text"});

        assertEquals("external_id AS id1, \"select\" AS id2, label AS id3",
                row.getOSelectList());
        assertEquals("id1, id2, id3", row.getOIdList());
        assertEquals("external_id, \"select\", label", row.getOOrderBy());
        assertEquals("id1 DESC, id2 DESC, id3 DESC", row.getOOrderByDesc());
        assertEquals("ROW(ARRAY[NULL, id2::bigint, NULL]::bigint[],"
                + " ARRAY[NULL, NULL, id3::text]::text[],"
                + " ARRAY[id1::uuid, NULL, NULL]::uuid[], NULL::timestamptz[])"
                + "::dml_utils_data.migration_key", row.getOPackExpression());
    }

    /**
     * A key column literally named {@code id1} collides with the generated alias,
     * so the select list aliases in key order, the window order by keeps the raw
     * (input) column names, and only the top-level descending order by uses the
     * idN aliases.
     */
    @Test
    void describesAKeyColumnThatCollidesWithAnIdAlias() {
        BoundaryKeyDescriptorSqlRecord row = describe(
                new String[]{"x", "id1"}, new String[]{"bigint", "bigint"});

        assertEquals("x AS id1, id1 AS id2", row.getOSelectList());
        assertEquals("id1, id2", row.getOIdList());
        assertEquals("x, id1", row.getOOrderBy());
        assertEquals("id1 DESC, id2 DESC", row.getOOrderByDesc());

        // Execute a swapped collision instead: with key columns (id2, id1) the
        // correct fragment (id1 DESC, id2 DESC) orders by src.id2, then src.id1,
        // while a raw-name fragment (id2 DESC, id1 DESC) would resolve to the
        // output aliases and order by src.id1, then src.id2 -- a deterministic
        // difference, unlike a tie on the first column.
        BoundaryKeyDescriptorSqlRecord swapped = describe(
                new String[]{"id2", "id1"}, new String[]{"bigint", "bigint"});
        List<Long[]> ordered = dsl.fetch("SELECT " + swapped.getOSelectList()
                        + " FROM (VALUES (1::bigint, 2::bigint), (2::bigint, 1::bigint))"
                        + " AS src(id2, id1) ORDER BY " + swapped.getOOrderByDesc())
                .map(record -> new Long[]{record.get("id1", Long.class),
                        record.get("id2", Long.class)});
        assertEquals(2L, ordered.getFirst()[0],
                "the first key column is ordered by its own value");
        assertEquals(1L, ordered.getFirst()[1],
                "the second key column is ordered by its own value, not by the first alias");
    }

    /**
     * A missing or empty column or kind array, a length mismatch and a key of
     * more than three columns are rejected.
     */
    @Test
    void rejectsMissingEmptyMismatchedAndOversizedArrays() {
        assertSqlState("22023", () -> describe(null, new String[]{"bigint"}));
        assertSqlState("22023", () -> describe(new String[]{"k1"}, null));
        assertSqlState("22023", () -> describe(new String[]{}, new String[]{}));
        assertSqlState("22023", () -> describe(new String[]{"k1", "k2"},
                new String[]{"bigint"}));
        assertSqlState("22023", () -> describe(new String[]{"k1"},
                new String[]{"bigint", "bigint"}));
        assertSqlState("22023", () -> describe(
                new String[]{"k1", "k2", "k3", "k4"},
                new String[]{"bigint", "bigint", "bigint", "bigint"}));
        // A repeated column would render a non-unique boundary key.
        assertSqlState("22023", () -> describe(
                new String[]{"k1", "k1"}, new String[]{"bigint", "bigint"}));
    }

    /**
     * Arrays that are not one-dimensional or not one-based are rejected. Java
     * arrays always map to a one-based one-dimensional array, so these cases use
     * raw SQL to construct the malformed values.
     */
    @Test
    void rejectsNonOneDimensionalOrNonOneBasedArrays() {
        assertSqlState("23514", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.boundary_key_descriptor_sql("
                        + "ARRAY[['k1']]::name[], ARRAY[['bigint']]::text[])"));
        assertSqlState("23514", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.boundary_key_descriptor_sql("
                        + "'[0:0]={k1}'::name[], '{bigint}'::text[])"));
        assertSqlState("23514", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.boundary_key_descriptor_sql("
                        + "'{k1}'::name[], '[0:0]={bigint}'::text[])"));
    }

    /**
     * An unsupported kind, a NULL kind and a NULL or blank column name are
     * rejected before any fragment is built.
     */
    @Test
    void rejectsUnsupportedKindsAndBlankColumnNames() {
        assertSqlState("22023", () -> describe(new String[]{"k1"},
                new String[]{"integer"}));
        assertSqlState("22023", () -> describe(new String[]{"k1"},
                new String[]{"BIGINT"}));
        assertSqlState("23514", () -> describe(new String[]{"k1"},
                new String[]{null}));
        assertSqlState("23514", () -> describe(new String[]{null},
                new String[]{"bigint"}));
        assertSqlState("22023", () -> describe(new String[]{""},
                new String[]{"bigint"}));
    }
}
