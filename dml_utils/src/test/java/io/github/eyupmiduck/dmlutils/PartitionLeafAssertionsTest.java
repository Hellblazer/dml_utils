package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_partitioned_leaf_relations} and
 * {@code assert_partitioned_leaf_privileges}: ordinary storage leaves pass, a
 * foreign-table leaf raises {@code 42809} and a leaf the caller cannot read
 * raises {@code 42501}.
 */
class PartitionLeafAssertionsTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";

    /**
     * Ordinary storage leaves of range, list, hash and multi-level parents pass
     * the relation check.
     */
    @Test
    void acceptsOrdinaryLeafRelations() {
        for (String table : new String[]{"test_partitioned_range", "test_partitioned_list",
                "test_partitioned_hash", "test_partitioned_multi"}) {
            assertDoesNotThrow(() -> Routines.assertPartitionedLeafRelations(
                    dsl.configuration(), relationOid(SCHEMA + "." + table)));
        }
    }

    /**
     * A foreign-table leaf has no ordinary heap and is rejected with
     * {@code 42809}.
     */
    @Test
    void rejectsAForeignLeafRelation() throws Exception {
        try {
            executeAsOwner("CREATE SERVER IF NOT EXISTS leaf_relation_foreign_server"
                    + " FOREIGN DATA WRAPPER postgres_fdw");
            executeAsOwner("CREATE FOREIGN TABLE " + SCHEMA + ".test_partitioned_foreign_leaf"
                    + " (id integer NOT NULL, payload text) SERVER leaf_relation_foreign_server");
            executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_foreign"
                    + " ATTACH PARTITION " + SCHEMA + ".test_partitioned_foreign_leaf"
                    + " FOR VALUES FROM (0) TO (100)");

            assertSqlState("42809", () -> Routines.assertPartitionedLeafRelations(
                    dsl.configuration(), relationOid(SCHEMA + ".test_partitioned_foreign")));
        } finally {
            executeAsOwner("DROP FOREIGN TABLE IF EXISTS " + SCHEMA
                    + ".test_partitioned_foreign_leaf");
            executeAsOwner("DROP SERVER IF EXISTS leaf_relation_foreign_server");
        }
    }

    /**
     * An unknown parent OID has no leaves, so both checks are no-ops.
     */
    @Test
    void toleratesAnUnknownParentOid() {
        assertDoesNotThrow(() -> Routines.assertPartitionedLeafRelations(
                dsl.configuration(), 0L));
        assertDoesNotThrow(() -> Routines.assertPartitionedLeafPrivileges(
                dsl.configuration(), 0L));
    }

    /**
     * Ordinary leaves are readable by the test role, so the privilege check
     * passes.
     */
    @Test
    void acceptsReadableLeaves() {
        assertDoesNotThrow(() -> Routines.assertPartitionedLeafPrivileges(
                dsl.configuration(), relationOid(SCHEMA + ".test_partitioned_range")));
    }

    /**
     * A caller that lacks USAGE on the leaf's schema cannot read the leaf
     * directly, so the pre-flight check rejects it with {@code 42501}.
     *
     * @throws Exception if the grant cannot be restored
     */
    @Test
    void rejectsALeafWhoseSchemaUsageIsMissing() throws Exception {
        long parent = relationOid(SCHEMA + ".test_partitioned_range");
        executeAsOwner("REVOKE USAGE ON SCHEMA " + SCHEMA
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.assertPartitionedLeafPrivileges(
                    dsl.configuration(), parent));
        } finally {
            executeAsOwner("GRANT USAGE ON SCHEMA " + SCHEMA
                    + " TO " + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * A leaf the caller cannot read raises {@code 42501}.
     */
    @Test
    void rejectsALeafTheCallerCannotRead() throws Exception {
        executeAsOwner("REVOKE SELECT ON " + SCHEMA + ".test_partitioned_list_a"
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.assertPartitionedLeafPrivileges(
                    dsl.configuration(), relationOid(SCHEMA + ".test_partitioned_list")));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + SCHEMA + ".test_partitioned_list_a TO "
                    + TEST_USER + ", dml_utils_caller");
        }
    }
}
