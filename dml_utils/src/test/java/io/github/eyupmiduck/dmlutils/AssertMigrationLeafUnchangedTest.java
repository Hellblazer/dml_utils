package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_migration_leaf_unchanged}: an unchanged
 * manifest leaf passes, while a missing run/leaf, a detached or rebound leaf, a
 * replaced leaf (different OID) and a rewritten blocks heap all fail closed with
 * {@code 55000}.
 */
class AssertMigrationLeafUnchangedTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String PARENT = "test_partitioned_range";
    private static final String MID = "test_partitioned_range_mid";

    /**
     * Empties the fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * Every manifest leaf of an unchanged run passes the check.
     */
    @Test
    void acceptsEveryManifestLeaf() {
        long runId = populatePartitionWise("leaf-unchanged-primary_key", PARENT, TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        for (int ordinal : leafOrdinals(runId)) {
            assertDoesNotThrow(() -> Routines.assertMigrationLeafUnchanged(
                    dsl.configuration(), runId, ordinal));
        }
    }

    /**
     * A missing run or a leaf ordinal that is not in the manifest raises
     * {@code 55000}.
     */
    @Test
    void rejectsAMissingRunOrLeaf() {
        long runId = populatePartitionWise("leaf-unchanged-primary_key", PARENT, TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        assertSqlState("55000", () -> Routines.assertMigrationLeafUnchanged(
                dsl.configuration(), -1L, 1));
        assertSqlState("55000", () -> Routines.assertMigrationLeafUnchanged(
                dsl.configuration(), runId, 999));
    }

    /**
     * A detached leaf is rejected and passes again once reattached.
     */
    @Test
    void rejectsADetachedLeafAndAcceptsItAfterReattach() throws Exception {
        long runId = populatePartitionWise("leaf-unchanged-primary_key", PARENT, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        int midOrdinal = leafOrdinal(runId, MID);

        executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARENT
                + " DETACH PARTITION " + PARTITION_SCHEMA + "." + MID);
        try {
            assertSqlState("55000", () -> Routines.assertMigrationLeafUnchanged(
                    dsl.configuration(), runId, midOrdinal));
        } finally {
            restoreMidPartition();
        }

        assertDoesNotThrow(() -> Routines.assertMigrationLeafUnchanged(
                dsl.configuration(), runId, midOrdinal));
    }

    /**
     * A leaf recreated under the same name and bound has a different OID, so the
     * manifest is stale and the check fails closed.
     */
    @Test
    void rejectsAReplacedLeaf() throws Exception {
        long runId = populatePartitionWise("leaf-unchanged-primary_key", PARENT, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        int midOrdinal = leafOrdinal(runId, MID);

        // Drop rather than rename: PostgreSQL keeps the old index names when a
        // table is renamed, so recreating the partition under the original name
        // would fail with 42P07 on the retained _pkey/_ordered_key_idx names.
        executeAsOwner("DROP TABLE " + PARTITION_SCHEMA + "." + MID);
        try {
            executeAsOwner("CREATE TABLE " + PARTITION_SCHEMA + "." + MID
                    + " PARTITION OF " + PARTITION_SCHEMA + "." + PARENT
                    + " FOR VALUES FROM (100) TO (200)");
            grantMid();

            assertSqlState("55000", () -> Routines.assertMigrationLeafUnchanged(
                    dsl.configuration(), runId, midOrdinal));
        } finally {
            executeAsOwner("DROP TABLE IF EXISTS " + PARTITION_SCHEMA + "." + MID);
            restoreMidPartition();
        }
    }

    /**
     * Reattaching a leaf under a different partition bound invalidates the stored
     * ranges even though the OID and name are unchanged.
     */
    @Test
    void rejectsAChangedLeafBound() throws Exception {
        long runId = populatePartitionWise("leaf-unchanged-primary_key", PARENT, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        int midOrdinal = leafOrdinal(runId, MID);

        executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARENT
                + " DETACH PARTITION " + PARTITION_SCHEMA + "." + MID);
        executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + PARENT
                + " ATTACH PARTITION " + PARTITION_SCHEMA + "." + MID
                + " FOR VALUES FROM (100) TO (199)");
        try {
            assertSqlState("55000", () -> Routines.assertMigrationLeafUnchanged(
                    dsl.configuration(), runId, midOrdinal));
        } finally {
            restoreMidPartition();
        }
    }

    /**
     * A blocks run addresses the heap by ctid, so a VACUUM FULL rewrite changes
     * the filepath and is rejected.
     */
    @Test
    void rejectsARewrittenBlocksHeap() throws Exception {
        insertRangeRows(0L, 1L, 2L, 4L);
        long runId = populatePartitionWise("leaf-unchanged-blocks", PARENT, TEMPLATE, ChunkingStrategy.blocks, 2, null);
        int lowOrdinal = leafOrdinal(runId, "test_partitioned_range_low");

        executeAsOwner("VACUUM FULL " + PARTITION_SCHEMA + ".test_partitioned_range_low");

        assertSqlState("55000", () -> Routines.assertMigrationLeafUnchanged(
                dsl.configuration(), runId, lowOrdinal));
    }


    private List<Integer> leafOrdinals(long runId) {
        return dsl.select(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .from(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .orderBy(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .fetch(MIGRATION_RUN_LEAF.LEAF_ORDINAL);
    }


    private void grantMid() throws Exception {
        executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                + PARTITION_SCHEMA + "." + MID + " TO " + TEST_USER
                + ", dml_utils_caller");
    }
}
