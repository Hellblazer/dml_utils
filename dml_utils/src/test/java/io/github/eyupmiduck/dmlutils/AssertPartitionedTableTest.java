package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_partitioned_table}: a declarative
 * partitioned parent of any supported shape is accepted, while a missing
 * relation raises {@code 42P01} and a relation that is not a partitioned table
 * raises {@code 42809}.
 */
class AssertPartitionedTableTest extends PartitionTestBase {

    private void assertPartitioned(String table) {
        assertDoesNotThrow(() -> Routines.assertPartitionedTable(
                dsl.configuration(), PARTITION_SCHEMA, table));
    }

    /**
     * Range, list, hash, multi-level and empty declarative parents are all
     * accepted.
     */
    @Test
    void acceptsEveryDeclarativePartitionShape() {
        assertPartitioned("test_partitioned_range");
        assertPartitioned("test_partitioned_list");
        assertPartitioned("test_partitioned_hash");
        assertPartitioned("test_partitioned_multi");
        assertPartitioned("test_partitioned_empty");
        assertPartitioned("test_partitioned");
    }

    /**
     * A missing schema or table is reported as undefined_table, because the
     * catalog lookup does not require the schema to exist first.
     */
    @Test
    void rejectsAMissingSchemaOrTable() {
        assertSqlState("42P01", () -> Routines.assertPartitionedTable(
                dsl.configuration(), "no_such_schema", "test_bigint"));
        assertSqlState("42P01", () -> Routines.assertPartitionedTable(
                dsl.configuration(), PARTITION_SCHEMA, "no_such_table"));
    }

    /**
     * A plain table, a partitioned index and a sequence are not partitioned
     * tables and are rejected with wrong_object_type.
     */
    @Test
    void rejectsRelationsThatAreNotPartitionedTables() {
        assertSqlState("42809", () -> Routines.assertPartitionedTable(
                dsl.configuration(), PARTITION_SCHEMA, "test_bigint"));
        assertSqlState("42809", () -> Routines.assertPartitionedTable(
                dsl.configuration(), PARTITION_SCHEMA,
                "test_partitioned_range_ordered_key_idx"));
    }
}
