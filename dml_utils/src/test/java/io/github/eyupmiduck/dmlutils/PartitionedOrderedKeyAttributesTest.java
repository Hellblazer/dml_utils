package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.PartitionedOrderedKeyAttributesRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /**
     * The unusable parent indexes.
     *
     * @return the index definitions and their SQLSTATEs
     */
    private static java.util.stream.Stream<Arguments> invalidParentIndexes() {
        return java.util.stream.Stream.of(
                Arguments.of("ON ONLY index", "test_partitioned_range_on_only_idx",
                        "CREATE UNIQUE INDEX test_partitioned_range_on_only_idx ON ONLY "
                                + "dml_utils_fixtures.test_partitioned_range (id)", "55000"),
                Arguments.of("non-unique index", "test_partitioned_range_payload_idx",
                        "CREATE INDEX test_partitioned_range_payload_idx ON "
                                + "dml_utils_fixtures.test_partitioned_range (payload)",
                        "0A000"));
    }

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
     * An index that is not on the partitioned parent is {@code 42704} (including
     * one that exists on another, plain table), and a non-partitioned driving table
     * is {@code 0A000}.
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
     * A parent index that is ON ONLY reports the index-level {@code 55000}, and a
     * non-unique parent index is {@code 0A000}.
     *
     * @param description   the rejected index shape
     * @param index         the index name
     * @param definition    the CREATE INDEX statement
     * @param expectedState the expected SQLSTATE
     * @throws Exception if the index cannot be created or dropped
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidParentIndexes")
    void rejectsAnUnusableParentIndex(String description, String index, String definition,
                                      String expectedState) throws Exception {
        executeAsOwner(definition);
        try {
            assertSqlState(expectedState, () -> attributes("test_partitioned_range", index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
        }
    }
}
