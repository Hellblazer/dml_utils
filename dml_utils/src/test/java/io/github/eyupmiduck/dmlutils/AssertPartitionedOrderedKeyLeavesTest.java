package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.assert_partitioned_ordered_key_leaves}: it
 * validates the resolved parent index before the per-leaf walk (including for a
 * run with an empty manifest) and rejects a NULL run id through its domain.
 */
class AssertPartitionedOrderedKeyLeavesTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String PARENT_INDEX = "test_partitioned_range_ordered_key_idx";
    private static final String ON_ONLY_INDEX = "test_partitioned_range_parent_on_only_idx";

    /**
     * Empties the fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndRange();
    }

    /**
     * A valid parent index and manifest pass the check.
     */
    @Test
    void acceptsAValidManifest() {
        long runId = populatePartitionWise("key-leaves-valid", PARTITIONED_RANGE, TEMPLATE,
                ChunkingStrategy.ordered_key, 1, PARENT_INDEX);

        assertDoesNotThrow(() -> Routines.assertPartitionedOrderedKeyLeaves(
                dsl.configuration(), runId, PARENT_INDEX));
    }

    /**
     * An unusable (ON ONLY) parent index is reported at the parent level before the
     * leaf walk, so a run with no manifest cannot validate nothing.
     */
    @Test
    void rejectsAnUnusableParentIndexEvenWithoutLeaves() throws Exception {
        long runId = insertOrderedRun(uniqueLabel("key-leaves-on-only"), ON_ONLY_INDEX);
        executeAsOwner("CREATE UNIQUE INDEX " + ON_ONLY_INDEX + " ON ONLY "
                + PARTITION_SCHEMA + "." + PARTITIONED_RANGE + " (id)");
        try {
            assertEquals(0, dsl.fetchCount(MIGRATION_RUN_LEAF,
                            MIGRATION_RUN_LEAF.RUN_ID.eq(runId)),
                    "the run intentionally has no manifest");

            assertSqlState("55000", () -> Routines.assertPartitionedOrderedKeyLeaves(
                    dsl.configuration(), runId, ON_ONLY_INDEX));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + ON_ONLY_INDEX);
        }
    }

    /**
     * With a real manifest the parent check still wins: replacing the parent index
     * with an ON ONLY one reports the parent-level 55000, not a missing child index
     * for every leaf (the leaf-level 0A000 that the pre-check code produced).
     */
    @Test
    void reportsTheParentBeforeTheLeaves() throws Exception {
        long runId = populatePartitionWise("key-leaves-replaced", PARTITIONED_RANGE, TEMPLATE,
                ChunkingStrategy.ordered_key, 1, PARENT_INDEX);

        executeAsOwner("DROP INDEX " + PARTITION_SCHEMA + "." + PARENT_INDEX);
        try {
            executeAsOwner("CREATE UNIQUE INDEX " + PARENT_INDEX + " ON ONLY "
                    + PARTITION_SCHEMA + "." + PARTITIONED_RANGE + " (id)");

            assertSqlState("55000", () -> Routines.assertPartitionedOrderedKeyLeaves(
                    dsl.configuration(), runId, PARENT_INDEX));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + PARENT_INDEX);
            executeAsOwner("CREATE UNIQUE INDEX " + PARENT_INDEX + " ON "
                    + PARTITION_SCHEMA + "." + PARTITIONED_RANGE + " (id)");
        }
    }

    /**
     * A NULL run id cannot reach the body: the non-null domain rejects it before
     * the lookup, so it is not reported as a missing run.
     */
    @Test
    void rejectsANullRunId() {
        assertDomainViolation(() -> Routines.assertPartitionedOrderedKeyLeaves(
                dsl.configuration(), null, PARENT_INDEX));
    }

    /**
     * Inserts an ordered-key partition-wise run with no manifest, so the parent
     * check is the only thing that can validate anything.
     *
     * @param label the run label
     * @param index the ordered-key index name to store
     * @return the run id
     */
    private long insertOrderedRun(String label, String index) {
        return dsl.fetchOne("""
                        INSERT INTO dml_utils_data.migration_run
                            (label, sql_text, chunk_size, chunk_by, partition_wise, threads,
                             driving_table_schema_name, driving_table_name,
                             ordered_key_index, ordered_key_columns, ordered_key_kinds)
                        VALUES (?, 'SELECT 1', 1, 'ordered_key', true, 1,
                                ?, ?, ?, ARRAY['id']::name[], ARRAY['bigint'])
                        RETURNING run_id
                        """, label, PARTITION_SCHEMA, PARTITIONED_RANGE, index)
                .get(0, Long.class);
    }
}
