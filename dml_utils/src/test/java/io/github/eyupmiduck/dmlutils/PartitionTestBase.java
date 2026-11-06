package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;

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
     * Creates a partition-wise run over the range fixture and returns its id.
     *
     * @param labelPrefix a short unique label prefix
     * @param table       the partitioned parent name
     * @param sqlText     the chunk SQL template
     * @param chunkBy     the strategy applied inside each leaf
     * @param chunkSize   the chunk size
     * @param index       the ordered-key index, or null
     * @return the run id
     */
    protected long populatePartitionWise(String labelPrefix, String table, String sqlText,
                                         ChunkingStrategy chunkBy, int chunkSize,
                                         String index) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, table, uniqueLabel(labelPrefix), sqlText, chunkSize, 1,
                chunkBy, "t", 0, index, true);
    }

    /**
     * Returns the manifest ordinal of a leaf in a partition-wise run.
     *
     * @param runId    the run id
     * @param leafName the storage leaf name
     * @return the leaf ordinal
     */
    protected int leafOrdinal(long runId, String leafName) {
        return dsl.select(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .from(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_NAME.eq(leafName))
                .fetchOne(MIGRATION_RUN_LEAF.LEAF_ORDINAL);
    }

    /**
     * Returns one manifest leaf row.
     *
     * @param runId   the run id
     * @param ordinal the leaf ordinal
     * @return the leaf row
     */
    protected MigrationRunLeafRecord leaf(long runId, int ordinal) {
        return dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .fetchOne();
    }

    /**
     * Returns one manifest leaf's state.
     *
     * @param runId   the run id
     * @param ordinal the leaf ordinal
     * @return the leaf state
     */
    protected String leafState(long runId, int ordinal) {
        return leaf(runId, ordinal).getState();
    }

    /**
     * Inserts rows into the range-partitioned fixture by primary key.
     *
     * @param ids the ids to insert
     */
    protected void insertRangeRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_PARTITIONED_RANGE,
                            TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(id, "pending")
                    .execute();
        }
    }

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
                                + " JOIN pg_catalog.pg_class AS p ON p.oid = i.inhparent"
                                + " JOIN pg_catalog.pg_namespace AS pn ON pn.oid = p.relnamespace"
                                + " WHERE n.nspname = ? AND c.relname = ?"
                                + "   AND pn.nspname = ? AND p.relname = ?)",
                        PARTITION_SCHEMA, PARTITIONED_RANGE_MID,
                        PARTITION_SCHEMA, PARTITIONED_RANGE)
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
