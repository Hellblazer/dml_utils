package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.OrderedKeyAttributesRecord;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

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

    private List<OrderedKeyAttributesRecord> attributes(String index) {
        return Routines.orderedKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, TABLE, index);
    }

    private void assertRejected(String expectedSqlState, String index) {
        assertSqlState(expectedSqlState, () -> attributes(index));
    }

    /**
     * A composite (timestamptz, bigint) unique index resolves in index order
     * with each column's collapsed kind.
     */
    @Test
    void resolvesACompositeTimestamptzKey() {
        List<OrderedKeyAttributesRecord> rows =
                attributes("test_ordered_key_created_at_id_idx");

        assertEquals(2, rows.size());
        assertAttribute(rows.get(0), 1, "created_at", "timestamptz");
        assertAttribute(rows.get(1), 2, "id", "bigint");
    }

    /**
     * A single-column unique uuid index resolves to one key column.
     */
    @Test
    void resolvesASingleColumnUuidKey() {
        List<OrderedKeyAttributesRecord> rows =
                attributes("test_ordered_key_external_id_idx");

        assertEquals(1, rows.size());
        assertAttribute(rows.get(0), 1, "external_id", "uuid");
    }

    /**
     * The table's primary-key index is itself a valid unique btree index, so it
     * resolves like any other ordered key.
     */
    @Test
    void resolvesThePrimaryKeyIndex() {
        List<OrderedKeyAttributesRecord> rows = attributes("test_ordered_key_pkey");

        assertEquals(1, rows.size());
        assertAttribute(rows.get(0), 1, "id", "bigint");
    }

    /**
     * INCLUDE columns are not part of the key order and are ignored.
     */
    @Test
    void ignoresIncludedColumns() {
        List<OrderedKeyAttributesRecord> rows =
                attributes("test_ordered_key_include_idx");

        assertEquals(2, rows.size());
        assertAttribute(rows.get(0), 1, "created_at", "timestamptz");
        assertAttribute(rows.get(1), 2, "id", "bigint");
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

        assertTrue(error.getMessage().contains("exists on table"),
                () -> "unexpected message: " + error.getMessage());
        assertTrue(error.getMessage().contains("test_bigint"),
                () -> "unexpected message: " + error.getMessage());
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
