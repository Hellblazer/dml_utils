package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.OrderedKeyColumnAttributesRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.ordered_key_column_attributes}: an index OID
 * resolves to its key columns in index order, ignoring INCLUDE columns, while the
 * per-column rules (supported kind, NOT NULL, default btree opclass, column
 * collation) are enforced with their documented SQLSTATEs.
 */
class OrderedKeyColumnAttributesTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_ordered_key";


    private List<OrderedKeyColumnAttributesRecord> columns(String table, String index) {
        return Routines.orderedKeyColumnAttributes(dsl.configuration(),
                relationOid(SCHEMA + "." + index), SCHEMA, table, index);
    }

    private void assertRejected(String expectedSqlState, String index) {
        assertSqlState(expectedSqlState, () -> columns(TABLE, index));
    }

    /**
     * The composite key resolves in index order with its collapsed kinds.
     */
    @Test
    void resolvesCompositeColumnsInIndexOrder() {
        List<OrderedKeyColumnAttributesRecord> rows =
                columns(TABLE, "test_ordered_key_created_at_id_idx");

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).getOrdinality());
        assertEquals("created_at", rows.get(0).getColumnName());
        assertEquals("timestamptz", rows.get(0).getKeyKind());
        assertEquals(2, rows.get(1).getOrdinality());
        assertEquals("id", rows.get(1).getColumnName());
        assertEquals("bigint", rows.get(1).getKeyKind());
    }

    /**
     * INCLUDE columns are ignored.
     */
    @Test
    void ignoresIncludedColumns() {
        List<OrderedKeyColumnAttributesRecord> rows =
                columns(TABLE, "test_ordered_key_include_idx");

        assertEquals(2, rows.size());
        assertEquals("created_at", rows.get(0).getColumnName());
        assertEquals("id", rows.get(1).getColumnName());
    }

    /**
     * The helper resolves columns without rechecking index-level uniqueness; the
     * caller validates the index first. This pins that separation.
     */
    @Test
    void resolvesColumnsWithoutRecheckingIndexShape() {
        List<OrderedKeyColumnAttributesRecord> rows =
                columns(TABLE, "test_ordered_key_nonunique_idx");

        assertEquals(1, rows.size());
        assertEquals("label", rows.getFirst().getColumnName());
        assertEquals("text", rows.getFirst().getKeyKind());
    }

    /**
     * A missing index OID is {@code 42704}.
     */
    @Test
    void rejectsAMissingIndex() {
        assertSqlState("42704", () -> Routines.orderedKeyColumnAttributes(
                dsl.configuration(), 0L, SCHEMA, TABLE, "no_such_index"));
    }

    /**
     * Unsupported types, nullable columns, non-default opclasses and collation
     * mismatches are rejected.
     */
    @Test
    void rejectsUnusableColumns() {
        assertRejected("22023", "test_ordered_key_numeric_idx");
        assertRejected("0A000", "test_ordered_key_nullable_idx");
        assertRejected("0A000", "test_ordered_key_opclass_idx");
        assertRejected("0A000", "test_ordered_key_collation_idx");
    }
}
