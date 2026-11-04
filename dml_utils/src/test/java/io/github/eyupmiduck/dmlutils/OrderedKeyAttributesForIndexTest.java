package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.OrderedKeyAttributesForIndexRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@code dml_utils_lib.ordered_key_attributes_for_index}: an index OID
 * is validated against every ordered-key shape rule and resolves to its key
 * columns, types and collapsed kinds in index order. The caller-supplied names
 * are used only for error messages, which is exercised through the mismatched-OID
 * case.
 */
class OrderedKeyAttributesForIndexTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_ordered_key";


    private List<OrderedKeyAttributesForIndexRecord> attributes(String table, String index) {
        return Routines.orderedKeyAttributesForIndex(dsl.configuration(),
                relationOid(SCHEMA + "." + index), SCHEMA, table, index);
    }

    private void assertRejected(String expectedSqlState, String index) {
        assertSqlState(expectedSqlState, () -> attributes(TABLE, index));
    }

    /**
     * The two-column (timestamptz, bigint) key resolves in index order with each
     * column's oid, type and collapsed kind.
     */
    @Test
    void resolvesACompositeKeyInIndexOrder() {
        List<OrderedKeyAttributesForIndexRecord> rows =
                attributes(TABLE, "test_ordered_key_created_at_id_idx");

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).getOrdinality());
        assertEquals("created_at", rows.get(0).getColumnName());
        assertEquals("timestamptz", rows.get(0).getKeyKind());
        assertNotNull(rows.get(0).getColumnOid());
        assertEquals(2, rows.get(1).getOrdinality());
        assertEquals("id", rows.get(1).getColumnName());
        assertEquals("bigint", rows.get(1).getKeyKind());
        assertNotNull(rows.get(1).getColumnOid());
    }

    /**
     * The single-column uuid, text and timestamptz keys each resolve to one row.
     */
    @Test
    void resolvesSingleColumnKeysOfEverySupportedKind() {
        List<OrderedKeyAttributesForIndexRecord> uuid =
                attributes(TABLE, "test_ordered_key_external_id_idx");
        assertEquals(1, uuid.size());
        assertEquals("external_id", uuid.getFirst().getColumnName());
        assertEquals("uuid", uuid.getFirst().getKeyKind());

        List<OrderedKeyAttributesForIndexRecord> text =
                attributes("test_ordered_key_text", "test_ordered_key_text_pkey");
        assertEquals(1, text.size());
        assertEquals("natural_key", text.getFirst().getColumnName());
        assertEquals("text", text.getFirst().getKeyKind());

        List<OrderedKeyAttributesForIndexRecord> timestamptz =
                attributes("test_ordered_key_timestamptz",
                        "test_ordered_key_timestamptz_created_at_idx");
        assertEquals(1, timestamptz.size());
        assertEquals("created_at", timestamptz.getFirst().getColumnName());
        assertEquals("timestamptz", timestamptz.getFirst().getKeyKind());
    }

    /**
     * INCLUDE columns are not key columns and are ignored.
     */
    @Test
    void ignoresIncludedColumns() {
        List<OrderedKeyAttributesForIndexRecord> rows =
                attributes(TABLE, "test_ordered_key_include_idx");

        assertEquals(2, rows.size());
        assertEquals("created_at", rows.get(0).getColumnName());
        assertEquals("id", rows.get(1).getColumnName());
    }

    /**
     * Every unsafe index shape is rejected with its documented SQLSTATE: a
     * non-btree, non-unique, expression, partial, DESC, NULLS FIRST, nullable,
     * non-default opclass or collation-mismatched index is {@code 0A000}, while a
     * numeric key and an index with more than three key columns are {@code 22023}.
     */
    @Test
    void rejectsEveryUnsafeIndexShape() {
        assertRejected("0A000", "test_ordered_key_hash_idx");
        assertRejected("0A000", "test_ordered_key_nonunique_idx");
        assertRejected("0A000", "test_ordered_key_expression_idx");
        assertRejected("0A000", "test_ordered_key_partial_idx");
        assertRejected("0A000", "test_ordered_key_desc_idx");
        assertRejected("0A000", "test_ordered_key_nulls_first_idx");
        assertRejected("0A000", "test_ordered_key_nullable_idx");
        assertRejected("0A000", "test_ordered_key_opclass_idx");
        assertRejected("0A000", "test_ordered_key_collation_idx");
        assertRejected("22023", "test_ordered_key_numeric_idx");
        assertRejected("22023", "test_ordered_key_four_idx");
    }

    /**
     * A missing index OID and an OID that belongs to a different table are
     * rejected with {@code 42704}; the supplied names only affect the message.
     */
    @Test
    void rejectsAMissingOrMismatchedIndexOid() {
        assertSqlState("42704", () -> Routines.orderedKeyAttributesForIndex(
                dsl.configuration(), 0L, SCHEMA, TABLE, "no_such_index"));
        assertSqlState("42704", () -> Routines.orderedKeyAttributesForIndex(
                dsl.configuration(), relationOid(SCHEMA + "." + "test_bigint_pkey"),
                SCHEMA, TABLE, "test_ordered_key_pkey"));
    }

    /**
     * An index built with ON ONLY is not valid, ready and live, so it fails closed
     * with {@code 55000}.
     */
    @Test
    void rejectsAnOnOnlyIndex() throws Exception {
        String index = "test_ordered_key_on_only_idx";
        executeAsOwner("CREATE UNIQUE INDEX " + index + " ON ONLY "
                + SCHEMA + ".test_partitioned_range (id)");
        try {
            assertSqlState("55000", () -> Routines.orderedKeyAttributesForIndex(
                    dsl.configuration(), relationOid(SCHEMA + "." + index),
                    SCHEMA, "test_partitioned_range", index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + SCHEMA + "." + index);
        }
    }
}
