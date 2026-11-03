package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.PartitionedOrderedKeyAttributesRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@code dml_utils_lib.partitioned_ordered_key_attributes}: a unique
 * btree index on a declarative partitioned parent resolves to the parent's key
 * columns after every storage leaf's matching child index has been validated.
 * Missing, non-partitioned and unusable shapes are rejected with the documented
 * SQLSTATEs.
 */
class PartitionedOrderedKeyAttributesTest extends PartitionTestBase {

    private List<PartitionedOrderedKeyAttributesRecord> attributes(String table, String index) {
        return Routines.partitionedOrderedKeyAttributes(dsl.configuration(),
                PARTITION_SCHEMA, table, index);
    }

    /**
     * A range parent's single-column index resolves to the parent key, and the
     * three leaf child indexes are validated on the way.
     */
    @Test
    void resolvesARangeParentKey() {
        List<PartitionedOrderedKeyAttributesRecord> rows =
                attributes("test_partitioned_range", "test_partitioned_range_ordered_key_idx");

        assertEquals(1, rows.size());
        assertEquals(1, rows.getFirst().getOrdinality());
        assertEquals("id", rows.getFirst().getColumnName());
        assertEquals("bigint", rows.getFirst().getKeyKind());
        assertNotNull(rows.getFirst().getColumnOid());
    }

    /**
     * A multi-level parent index resolves to its two-column key after the
     * recursive leaf-index walk.
     */
    @Test
    void resolvesAMultiLevelParentKey() {
        List<PartitionedOrderedKeyAttributesRecord> rows = attributes(
                "test_partitioned_multi", "test_partitioned_multi_ordered_key_idx");

        assertEquals(2, rows.size());
        assertEquals("id", rows.get(0).getColumnName());
        assertEquals("bigint", rows.get(0).getKeyKind());
        assertEquals("bucket", rows.get(1).getColumnName());
        assertEquals("bigint", rows.get(1).getKeyKind());
    }

    /**
     * A unique index on a single-leaf partitioned parent also resolves.
     */
    @Test
    void resolvesASingleLeafParentKey() {
        List<PartitionedOrderedKeyAttributesRecord> rows =
                attributes("test_partitioned", "test_partitioned_id_idx");

        assertEquals(1, rows.size());
        assertEquals("id", rows.getFirst().getColumnName());
        assertEquals("bigint", rows.getFirst().getKeyKind());
    }

    /**
     * A missing index is {@code 42704}; a plain-table index is {@code 0A000}.
     */
    @Test
    void rejectsMissingAndNonPartitionedIndexes() {
        assertSqlState("42704", () -> attributes(
                "test_partitioned_range", "no_such_index"));
        assertSqlState("42704", () -> attributes(
                "test_partitioned_range", "test_ordered_key_created_at_id_idx"));
        assertSqlState("0A000", () -> attributes("test_bigint", "test_bigint_pkey"));
    }

    /**
     * A missing schema or table raises {@code 3F000} / {@code 42P01}.
     */
    @Test
    void rejectsAMissingSchemaOrTable() {
        assertSqlState("3F000", () -> Routines.partitionedOrderedKeyAttributes(
                dsl.configuration(), "no_such_schema", "test_partitioned_range",
                "test_partitioned_range_ordered_key_idx"));
        assertSqlState("42P01", () -> Routines.partitionedOrderedKeyAttributes(
                dsl.configuration(), PARTITION_SCHEMA, "no_such_table",
                "test_partitioned_range_ordered_key_idx"));
    }

    /**
     * An ON ONLY parent index is invalid and fails closed with {@code 55000}
     * before the leaf walk.
     */
    @Test
    void rejectsAnOnOnlyParentIndex() throws Exception {
        String index = "test_partitioned_range_on_only_idx";
        executeAsOwner("CREATE UNIQUE INDEX " + index + " ON ONLY "
                + PARTITION_SCHEMA + ".test_partitioned_range (id)");
        try {
            assertSqlState("55000", () -> attributes("test_partitioned_range", index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
        }
    }

    /**
     * A non-unique parent index is rejected with {@code 0A000}.
     */
    @Test
    void rejectsANonUniqueParentIndex() throws Exception {
        String index = "test_partitioned_range_payload_idx";
        executeAsOwner("CREATE INDEX " + index + " ON "
                + PARTITION_SCHEMA + ".test_partitioned_range (payload)");
        try {
            assertSqlState("0A000", () -> attributes("test_partitioned_range", index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
        }
    }
}
