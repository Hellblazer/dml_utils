package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.OrderedKeyAttributesRecord;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the ordered-key catalog resolution: a unique btree index on one to
 * three simple NOT NULL columns of a supported type resolves to its key columns
 * and kinds in index order, while every unsafe index or table shape is rejected
 * with a documented SQLSTATE.
 *
 * <p>The not-valid/not-ready/not-live branch ({@code 55000}) is deliberately not
 * exercised: it needs catalog surgery the test role cannot perform, and exists
 * as a fail-closed guard for a failed concurrent build.
 */
class OrderedKeyCatalogTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_ordered_key";

    private static void assertAttribute(OrderedKeyAttributesRecord row, int ordinality,
                                        String name, String kind) {
        assertEquals(ordinality, row.getOrdinality());
        assertEquals(name, row.getColumnName());
        assertEquals(kind, row.getKeyKind());
        assertNotNull(row.getColumnOid(), "the column type oid is returned");
    }

    /**
     * The resolvable index shapes.
     *
     * @return the index, its key columns and kinds
     */
    private static java.util.stream.Stream<Arguments> resolvableIndexes() {
        return java.util.stream.Stream.of(
                Arguments.of("composite timestamptz, bigint",
                        "test_ordered_key_created_at_id_idx",
                        new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"}),
                Arguments.of("single uuid", "test_ordered_key_external_id_idx",
                        new String[]{"external_id"}, new String[]{"uuid"}),
                Arguments.of("primary key", "test_ordered_key_pkey",
                        new String[]{"id"}, new String[]{"bigint"}),
                Arguments.of("INCLUDE columns", "test_ordered_key_include_idx",
                        new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"}));
    }

    private List<OrderedKeyAttributesRecord> attributes(String index) {
        return Routines.orderedKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, TABLE, index);
    }

    private void assertRejected(String expectedSqlState, String index) {
        assertSqlState(expectedSqlState, () -> attributes(index));
    }

    /**
     * Each supported unique btree index resolves to its key columns in index
     * order; INCLUDE columns are not part of the key.
     *
     * @param description the index shape
     * @param index       the index name
     * @param columns     the expected key columns
     * @param kinds       the expected collapsed kinds
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resolvableIndexes")
    void resolvesAKeyInIndexOrder(String description, String index, String[] columns,
                                  String[] kinds) {
        List<OrderedKeyAttributesRecord> rows = attributes(index);

        assertEquals(columns.length, rows.size());
        for (int i = 0; i < columns.length; i++) {
            assertAttribute(rows.get(i), i + 1, columns[i], kinds[i]);
        }
    }

    /**
     * The column and kind readers return the same key in index order.
     */
    @Test
    void returnsColumnsAndKindsInIndexOrder() {
        String[] columns = Routines.orderedKeyColumns(
                dsl.configuration(), FIXTURE_SCHEMA, TABLE,
                "test_ordered_key_created_at_id_idx");
        String[] kinds = Routines.orderedKeyKinds(
                dsl.configuration(), FIXTURE_SCHEMA, TABLE,
                "test_ordered_key_created_at_id_idx");

        assertArrayEquals(new String[]{"created_at", "id"}, columns);
        assertArrayEquals(new String[]{"timestamptz", "bigint"}, kinds);
    }

    /**
     * The assert wrapper accepts a usable index and rejects an unusable one.
     */
    @Test
    void assertWrapperAcceptsAUsableKeyAndRejectsAnUnusableOne() {
        Routines.assertUsableOrderedKey(
                dsl.configuration(), FIXTURE_SCHEMA, TABLE,
                "test_ordered_key_created_at_id_idx");

        assertSqlState("0A000", () -> Routines.assertUsableOrderedKey(
                dsl.configuration(), FIXTURE_SCHEMA, TABLE,
                "test_ordered_key_nonunique_idx"));
    }

    /**
     * A missing index, or an index that exists on a different table, raises
     * {@code 42704}.
     */
    @Test
    void rejectsAMissingIndexOnTheTable() {
        assertRejected("42704", "no_such_index");
        assertRejected("42704", "test_bigint_pkey");
    }

    /**
     * When the name resolves to an index on another table, the error's hint
     * names that table instead of leaving the caller with a dead end.
     */
    @Test
    void hintsAtAnIndexOnAnotherTable() {
        DataAccessException error = assertThrows(DataAccessException.class,
                () -> attributes("test_bigint_pkey"));

        assertTrue(error.getMessage().contains("Hint:"),
                () -> "expected a hint in the message: " + error.getMessage());
        assertTrue(error.getMessage().contains(
                        "exists on table " + FIXTURE_SCHEMA + ".test_bigint"),
                () -> "the hint should name the table that owns the index: " + error.getMessage());
    }

    /**
     * A missing schema or table raises {@code 3F000} / {@code 42P01} before the
     * index is resolved.
     */
    @Test
    void rejectsAMissingSchemaOrTable() {
        assertSqlState("3F000", () -> Routines.orderedKeyAttributes(
                dsl.configuration(), "no_such_schema", TABLE,
                "test_ordered_key_created_at_id_idx"));
        assertSqlState("42P01", () -> Routines.orderedKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "no_such_table",
                "test_ordered_key_created_at_id_idx"));
    }

    /**
     * A non-unique index raises {@code 0A000}.
     */
    @Test
    void rejectsANonUniqueIndex() {
        assertRejected("0A000", "test_ordered_key_nonunique_idx");
    }

    /**
     * A non-btree index raises {@code 0A000}.
     */
    @Test
    void rejectsANonBtreeIndex() {
        assertRejected("0A000", "test_ordered_key_hash_idx");
    }

    /**
     * A partial or expression index raises {@code 0A000}.
     */
    @Test
    void rejectsAPartialOrExpressionIndex() {
        assertRejected("0A000", "test_ordered_key_partial_idx");
        assertRejected("0A000", "test_ordered_key_expression_idx");
    }

    /**
     * A DESC or NULLS FIRST index raises {@code 0A000}, because the rendered
     * predicate only expresses ascending, NULLS LAST ranges.
     */
    @Test
    void rejectsANonAscendingIndex() {
        assertRejected("0A000", "test_ordered_key_desc_idx");
        assertRejected("0A000", "test_ordered_key_nulls_first_idx");
    }

    /**
     * A unique index on a nullable column raises {@code 0A000}: NULLs are
     * distinct, so the range predicate cannot order them.
     */
    @Test
    void rejectsANullableKeyColumn() {
        assertRejected("0A000", "test_ordered_key_nullable_idx");
    }

    /**
     * A valid three-column key is accepted: it is the documented maximum, and the
     * mixed-kind primary key of the composite fixture is a genuine unique index.
     */
    @Test
    void acceptsAThreeColumnKey() {
        assertArrayEquals(new String[]{"b", "a", "c"},
                Routines.orderedKeyColumns(dsl.configuration(), FIXTURE_SCHEMA,
                        "test_composite_three", "test_composite_three_pkey"));
        assertArrayEquals(new String[]{"bigint", "text", "uuid"},
                Routines.orderedKeyKinds(dsl.configuration(), FIXTURE_SCHEMA,
                        "test_composite_three", "test_composite_three_pkey"));
    }

    /**
     * A key of more than three columns raises {@code 22023}.
     */
    @Test
    void rejectsMoreThanThreeKeyColumns() {
        assertRejected("22023", "test_ordered_key_four_idx");
    }

    /**
     * A key column of an unsupported type raises {@code 22023}.
     */
    @Test
    void rejectsAnUnsupportedKeyType() {
        assertRejected("22023", "test_ordered_key_numeric_idx");
    }

    /**
     * A non-default btree operator class raises {@code 0A000}, because the
     * rendered predicate uses the type's default comparison.
     */
    @Test
    void rejectsANonDefaultOpclass() {
        assertRejected("0A000", "test_ordered_key_opclass_idx");
    }

    /**
     * An index whose collation differs from the column's raises {@code 0A000}.
     */
    @Test
    void rejectsACollationMismatch() {
        assertRejected("0A000", "test_ordered_key_collation_idx");
    }

    /**
     * A partitioned parent raises {@code 0A000}; the partition-wise epic owns
     * those tables.
     */
    @Test
    void rejectsAPartitionedTable() {
        assertSqlState("0A000", () -> Routines.orderedKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_partitioned",
                "test_partitioned_id_idx"));
    }

    /**
     * The index name is a non-null, non-blank domain, so a blank name is
     * rejected before the catalog is read.
     */
    @Test
    void rejectsABlankIndexName() {
        assertDomainViolation(() -> attributes("  "));
    }
}
