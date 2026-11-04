package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.resolve_partitioned_ordered_key_index}: a valid
 * unique index on a partitioned parent resolves to its OID, while missing,
 * non-partitioned and unusable parent shapes raise the documented SQLSTATE.
 */
class ResolvePartitionedOrderedKeyIndexTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";

    private long resolve(String table, String index) {
        return Routines.resolvePartitionedOrderedKeyIndex(dsl.configuration(),
                SCHEMA, table, index);
    }


    /**
     * A range, multi-level and single-leaf parent index each resolve to the
     * catalog OID.
     */
    @Test
    void resolvesValidParentIndexes() {
        assertEquals(relationOid(SCHEMA + "." + "test_partitioned_range_ordered_key_idx"),
                resolve("test_partitioned_range", "test_partitioned_range_ordered_key_idx"));
        assertEquals(relationOid(SCHEMA + "." + "test_partitioned_multi_ordered_key_idx"),
                resolve("test_partitioned_multi", "test_partitioned_multi_ordered_key_idx"));
        assertEquals(relationOid(SCHEMA + "." + "test_partitioned_id_idx"),
                resolve("test_partitioned", "test_partitioned_id_idx"));
    }

    /**
     * A missing index is {@code 42704}; a plain-table index is {@code 0A000}.
     */
    @Test
    void rejectsMissingAndNonPartitionedIndexes() {
        assertSqlState("42704", () -> resolve("test_partitioned_range", "no_such_index"));
        assertSqlState("42704", () -> resolve("test_partitioned_range",
                "test_ordered_key_created_at_id_idx"));
        assertSqlState("0A000", () -> resolve("test_bigint", "test_bigint_pkey"));
    }

    /**
     * A missing schema or table raises {@code 3F000} / {@code 42P01}.
     */
    @Test
    void rejectsAMissingSchemaOrTable() {
        assertSqlState("3F000", () -> Routines.resolvePartitionedOrderedKeyIndex(
                dsl.configuration(), "no_such_schema", "test_partitioned_range",
                "test_partitioned_range_ordered_key_idx"));
        assertSqlState("42P01", () -> Routines.resolvePartitionedOrderedKeyIndex(
                dsl.configuration(), SCHEMA, "no_such_table",
                "test_partitioned_range_ordered_key_idx"));
    }

    /**
     * An ON ONLY parent index is {@code 55000}; a non-unique parent index is
     * {@code 0A000}.
     */
    @Test
    void rejectsUnusableParentIndexes() throws Exception {
        String onOnly = "test_partitioned_range_resolve_on_only_idx";
        executeAsOwner("CREATE UNIQUE INDEX " + onOnly + " ON ONLY "
                + SCHEMA + ".test_partitioned_range (id)");
        try {
            assertSqlState("55000", () -> resolve("test_partitioned_range", onOnly));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + SCHEMA + "." + onOnly);
        }

        String nonUnique = "test_partitioned_range_resolve_payload_idx";
        executeAsOwner("CREATE INDEX " + nonUnique + " ON "
                + SCHEMA + ".test_partitioned_range (payload)");
        try {
            assertSqlState("0A000", () -> resolve("test_partitioned_range", nonUnique));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + SCHEMA + "." + nonUnique);
        }
    }
}
