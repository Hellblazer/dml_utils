package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_ordered_key_index_usable}: usable unique
 * btree indexes pass, while every index-level shape violation and a missing or
 * mismatched OID raise the documented SQLSTATE.
 */
class AssertOrderedKeyIndexUsableTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_ordered_key";


    private void assertUsable(String table, String index) {
        assertDoesNotThrow(() -> Routines.assertOrderedKeyIndexUsable(
                dsl.configuration(), relationOid(SCHEMA + "." + index), SCHEMA, table, index));
    }

    private void assertRejected(String expectedSqlState, String index) {
        assertSqlState(expectedSqlState, () -> Routines.assertOrderedKeyIndexUsable(
                dsl.configuration(), relationOid(SCHEMA + "." + index), SCHEMA, TABLE, index));
    }

    /**
     * The composite, single-column, INCLUDE and primary-key indexes are all
     * usable.
     */
    @Test
    void acceptsUsableIndexes() {
        assertUsable(TABLE, "test_ordered_key_created_at_id_idx");
        assertUsable(TABLE, "test_ordered_key_external_id_idx");
        assertUsable(TABLE, "test_ordered_key_include_idx");
        assertUsable(TABLE, "test_ordered_key_pkey");
    }

    /**
     * Every index-level shape violation is rejected with its documented code.
     */
    @Test
    void rejectsUnsafeIndexShapes() {
        assertRejected("0A000", "test_ordered_key_hash_idx");
        assertRejected("0A000", "test_ordered_key_nonunique_idx");
        assertRejected("0A000", "test_ordered_key_expression_idx");
        assertRejected("0A000", "test_ordered_key_partial_idx");
        assertRejected("0A000", "test_ordered_key_desc_idx");
        assertRejected("0A000", "test_ordered_key_nulls_first_idx");
        assertRejected("22023", "test_ordered_key_four_idx");
    }

    /**
     * A missing OID and an OID that belongs to a different table are rejected
     * with {@code 42704}.
     */
    @Test
    void rejectsMissingAndMismatchedIndexes() {
        assertSqlState("42704", () -> Routines.assertOrderedKeyIndexUsable(
                dsl.configuration(), 0L, SCHEMA, TABLE, "no_such_index"));
        assertSqlState("42704", () -> Routines.assertOrderedKeyIndexUsable(
                dsl.configuration(), relationOid(SCHEMA + "." + "test_bigint_pkey"),
                SCHEMA, TABLE, "test_ordered_key_pkey"));
        // The OID is valid and belongs to the table, but the name resolves to a
        // different index on that table.
        assertSqlState("42704", () -> Routines.assertOrderedKeyIndexUsable(
                dsl.configuration(),
                relationOid(SCHEMA + "." + "test_ordered_key_created_at_id_idx"),
                SCHEMA, TABLE, "test_ordered_key_external_id_idx"));
    }

    /**
     * An ON ONLY index is not valid, ready and live and fails closed with
     * {@code 55000}.
     */
    @Test
    void rejectsAnOnOnlyIndex() throws Exception {
        String index = "test_ordered_key_usable_on_only_idx";
        executeAsOwner("CREATE UNIQUE INDEX " + index + " ON ONLY "
                + SCHEMA + ".test_partitioned_range (id)");
        try {
            assertSqlState("55000", () -> Routines.assertOrderedKeyIndexUsable(
                    dsl.configuration(), relationOid(SCHEMA + "." + index),
                    SCHEMA, "test_partitioned_range", index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + SCHEMA + "." + index);
        }
    }
}
