package io.github.eyupmiduck.dmlutils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared fixture support for the partition-wise tests: owner-side DDL, the
 * canonical range fixture names, and a tolerant restore of the middle partition
 * used by the detach/attach cases.
 */
abstract class PartitionTestBase extends PostgresTestBase {

    static final String PARTITION_SCHEMA = "dml_utils_fixtures";
    static final String PARTITIONED_RANGE = "test_partitioned_range";
    static final String PARTITIONED_RANGE_MID = "test_partitioned_range_mid";

    /**
     * Executes one statement as the owner.
     *
     * @param sql the statement to run
     * @throws SQLException if the statement fails
     */
    protected void executeAsOwner(String sql) throws SQLException {
        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Whether the named relation exists in the fixture schema.
     *
     * @param name the relation name
     * @return true when it exists
     */
    protected boolean relationExists(String name) {
        return Boolean.TRUE.equals(dsl.fetchOne(
                        "SELECT pg_catalog.to_regclass(?) IS NOT NULL",
                        PARTITION_SCHEMA + "." + name)
                .get(0, Boolean.class));
    }

    /**
     * Whether the middle range leaf is currently attached to its parent.
     *
     * @return true when attached
     */
    protected boolean partitionAttached() {
        return Boolean.TRUE.equals(dsl.fetchOne(
                        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits AS i"
                                + " JOIN pg_catalog.pg_class AS c ON c.oid = i.inhrelid"
                                + " JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = ? AND c.relname = ?)",
                        PARTITION_SCHEMA, PARTITIONED_RANGE_MID)
                .get(0, Boolean.class));
    }

    /**
     * Restores the middle range leaf to its canonical attached state, recreating
     * it when a test dropped it, and re-applies the fixture grants.
     *
     * @throws SQLException if the restore fails
     */
    protected void restoreMidPartition() throws SQLException {
        if (partitionAttached()) {
            executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE
                    + " DETACH PARTITION " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID);
        }
        if (!relationExists(PARTITIONED_RANGE_MID)) {
            executeAsOwner("CREATE TABLE " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID
                    + " PARTITION OF " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE
                    + " FOR VALUES FROM (100) TO (200)");
        } else {
            executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE
                    + " ATTACH PARTITION " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID
                    + " FOR VALUES FROM (100) TO (200)");
        }
        executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID
                + " TO " + TEST_USER + ", dml_utils_caller");
    }
}
