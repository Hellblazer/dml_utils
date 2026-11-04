package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_partitioned_ordered_key_child_indexes}: a
 * valid range or multi-level parent index has a child index for every storage
 * leaf, while a leaf without a matching child fails closed with {@code 0A000}.
 */
class AssertPartitionedOrderedKeyChildIndexesTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";


    /**
     * A range parent's three leaves and a multi-level parent's three storage
     * leaves all have matching child indexes.
     */
    @Test
    void acceptsEveryStorageLeafWithAChildIndex() {
        assertDoesNotThrow(() -> Routines.assertPartitionedOrderedKeyChildIndexes(
                dsl.configuration(), relationOid(SCHEMA + "." + "test_partitioned_range_ordered_key_idx"),
                SCHEMA, "test_partitioned_range", "test_partitioned_range_ordered_key_idx"));
        assertDoesNotThrow(() -> Routines.assertPartitionedOrderedKeyChildIndexes(
                dsl.configuration(), relationOid(SCHEMA + "." + "test_partitioned_multi_ordered_key_idx"),
                SCHEMA, "test_partitioned_multi", "test_partitioned_multi_ordered_key_idx"));
    }

    /**
     * A leaf set that the index tree does not cover has no matching child index
     * and raises {@code 0A000}.
     */
    @Test
    void rejectsLeavesWithoutAMatchingChildIndex() {
        assertSqlState("0A000", () -> Routines.assertPartitionedOrderedKeyChildIndexes(
                dsl.configuration(), relationOid(SCHEMA + "." + "test_partitioned_range_ordered_key_idx"),
                SCHEMA, "test_partitioned_multi", "test_partitioned_multi_ordered_key_idx"));
        assertSqlState("0A000", () -> Routines.assertPartitionedOrderedKeyChildIndexes(
                dsl.configuration(), 0L,
                SCHEMA, "test_partitioned_range", "test_partitioned_range_ordered_key_idx"));
    }
}
