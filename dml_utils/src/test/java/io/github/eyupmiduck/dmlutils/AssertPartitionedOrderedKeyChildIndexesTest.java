package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_partitioned_ordered_key_child_indexes}: a
 * valid range or multi-level parent index has a child index for every storage
 * leaf, while a stale, zero or mismatched parent OID fails with {@code 42704}
 * before the leaf walk (the missing-child {@code 0A000} path is covered by
 * {@code PartitionWiseValidationTest}).
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
     * A parent OID that is zero or belongs to a different (schema, table, index)
     * identity is rejected with {@code 42704} instead of reporting a missing
     * child index for every leaf.
     */
    @Test
    void rejectsMissingOrMismatchedParentIndexes() {
        assertSqlState("42704", () -> Routines.assertPartitionedOrderedKeyChildIndexes(
                dsl.configuration(), relationOid(SCHEMA + "." + "test_partitioned_range_ordered_key_idx"),
                SCHEMA, "test_partitioned_multi", "test_partitioned_multi_ordered_key_idx"));
        assertSqlState("42704", () -> Routines.assertPartitionedOrderedKeyChildIndexes(
                dsl.configuration(), 0L,
                SCHEMA, "test_partitioned_range", "test_partitioned_range_ordered_key_idx"));
    }
}
