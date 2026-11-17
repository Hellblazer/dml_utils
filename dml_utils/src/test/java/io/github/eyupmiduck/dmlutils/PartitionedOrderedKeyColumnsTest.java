package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies {@code dml_utils_lib.partitioned_ordered_key_columns}: a partitioned
 * parent's ordered-key index resolves to its key columns in key order for a
 * single-column and a composite index, while a missing index raises
 * {@code 42704} and a non-partitioned table raises {@code 0A000}.
 */
class PartitionedOrderedKeyColumnsTest extends PartitionTestBase {

    /**
     * A single-column parent index resolves to that column.
     */
    @Test
    void resolvesASingleColumnParentKey() {
        assertArrayEquals(new String[]{"id"},
                Routines.partitionedOrderedKeyColumns(dsl.configuration(),
                        PARTITION_SCHEMA, PARTITIONED_RANGE,
                        "test_partitioned_range_ordered_key_idx"));
    }

    /**
     * A multi-level parent's composite unique index resolves both columns in key
     * order, after every storage leaf's child index is validated.
     */
    @Test
    void resolvesACompositeParentKeyInKeyOrder() {
        assertArrayEquals(new String[]{"id", "bucket"},
                Routines.partitionedOrderedKeyColumns(dsl.configuration(),
                        PARTITION_SCHEMA, "test_partitioned_multi",
                        "test_partitioned_multi_ordered_key_idx"));
    }

    /**
     * A missing index is {@code 42704}; an index on an ordinary table is
     * {@code 0A000}, because partition-wise ordered keys need a partitioned
     * parent.
     */
    @Test
    void rejectsAMissingIndexAndANonPartitionedParent() {
        assertSqlState("42704", () -> Routines.partitionedOrderedKeyColumns(
                dsl.configuration(), PARTITION_SCHEMA, PARTITIONED_RANGE,
                "no_such_index"));
        assertSqlState("0A000", () -> Routines.partitionedOrderedKeyColumns(
                dsl.configuration(), PARTITION_SCHEMA, "test_bigint",
                "test_bigint_pkey"));
    }
}
