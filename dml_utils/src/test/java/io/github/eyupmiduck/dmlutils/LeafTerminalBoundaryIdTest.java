package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMulti.TEST_PARTITIONED_MULTI;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMultiHigh.TEST_PARTITIONED_MULTI_HIGH;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMultiLowH0.TEST_PARTITIONED_MULTI_LOW_H0;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMultiLowH1.TEST_PARTITIONED_MULTI_LOW_H1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.leaf_terminal_boundary_id}: a key-strategy leaf
 * returns its last row in the run's key order, an empty leaf returns NULL, a
 * blocks leaf returns the one-past-end block number, and a missing run or leaf
 * fails with {@code 55000}.
 */
class LeafTerminalBoundaryIdTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Empties both partition fixtures between tests.
     */
    @BeforeEach
    void reset() {
        resetRanges();
    }

    /**
     * A primary-key run returns the maximum key of each non-empty leaf and NULL
     * for the empty middle leaf.
     */
    @Test
    void returnsTheHighWaterKeyPerPrimaryKeyLeaf() {
        insertRangeRows(0L, 1L, 2L, 4L, 200L, 202L);
        long runId = createRangeRun(ChunkingStrategy.primary_key, null);

        assertEquals(4L, terminal(runId, "test_partitioned_range_low")
                .getBigintValues()[0]);
        assertTrue(terminalIsNull(runId, "test_partitioned_range_mid"),
                "an empty leaf has no high-water key");
        assertEquals(202L, terminal(runId, "test_partitioned_range_high")
                .getBigintValues()[0]);
    }

    /**
     * An ordered-key run uses the stored descriptor, so a composite key returns
     * both columns in key order for each leaf.
     */
    @Test
    void returnsTheHighWaterKeyPerOrderedKeyLeaf() {
        dsl.insertInto(TEST_PARTITIONED_MULTI,
                        TEST_PARTITIONED_MULTI.ID, TEST_PARTITIONED_MULTI.BUCKET,
                        TEST_PARTITIONED_MULTI.PAYLOAD)
                .values(0L, 0, "pending")
                .values(0L, 1, "pending")
                .values(100L, 0, "pending")
                .execute();
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_multi",
                uniqueLabel("terminal-ordered"), TEMPLATE, 2, 1,
                ChunkingStrategy.ordered_key, "t", 0,
                "test_partitioned_multi_ordered_key_idx", true, 3, 1000);

        for (Table<?> leaf : List.of(TEST_PARTITIONED_MULTI_LOW_H0,
                TEST_PARTITIONED_MULTI_LOW_H1, TEST_PARTITIONED_MULTI_HIGH)) {
            Long[] expected = lastKey(leaf);
            if (expected == null) {
                assertTrue(terminalIsNull(runId, leaf.getName()),
                        "an empty leaf has no high-water key");
            } else {
                MigrationKeyRecord actual = terminal(runId, leaf.getName());
                assertEquals(expected[0], actual.getBigintValues()[0],
                        "id for " + leaf.getName());
                assertEquals(expected[1], actual.getBigintValues()[1],
                        "bucket for " + leaf.getName());
            }
        }
    }

    /**
     * A blocks run returns the one-past-end block number of the leaf's heap.
     */
    @Test
    void returnsTheOnePastEndBlockForABlocksLeaf() {
        insertRangeRows(0L, 1L, 2L, 4L, 200L, 202L);
        long runId = createRangeRun(ChunkingStrategy.blocks, null);

        assertEquals(leafBlocks("test_partitioned_range_low"),
                terminal(runId, "test_partitioned_range_low").getBigintValues()[0]);
        assertTrue(terminalIsNull(runId, "test_partitioned_range_mid"),
                "an empty leaf has no terminal block");
        assertEquals(leafBlocks("test_partitioned_range_high"),
                terminal(runId, "test_partitioned_range_high").getBigintValues()[0]);
    }

    /**
     * A run without a manifest leaf, and a manifest without the ordinal, both
     * raise {@code 55000}.
     */
    @Test
    void rejectsAMissingRunOrLeaf() {
        long runId = createRangeRun(ChunkingStrategy.primary_key, null);
        assertSqlState("55000", () -> Routines.leafTerminalBoundaryId(
                dsl.configuration(), -1L, 1));
        assertSqlState("55000", () -> Routines.leafTerminalBoundaryId(
                dsl.configuration(), runId, 999));
    }

    /**
     * A single-relation run has no leaf manifest, so it has no leaf terminal.
     */
    @Test
    void rejectsASingleRelationRun() {
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range",
                uniqueLabel("terminal-plain"), TEMPLATE, 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

        assertSqlState("55000", () -> Routines.leafTerminalBoundaryId(
                dsl.configuration(), runId, 1));
    }

    private long createRangeRun(ChunkingStrategy chunkBy, String index) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range",
                uniqueLabel("terminal-" + chunkBy), TEMPLATE, 2, 1,
                chunkBy, "t", 0, index, true, 3, 1000);
    }

    private MigrationKeyRecord terminal(long runId, String leafName) {
        return Routines.leafTerminalBoundaryId(dsl.configuration(), runId,
                leafOrdinal(runId, leafName));
    }

    private boolean terminalIsNull(long runId, String leafName) {
        return Boolean.TRUE.equals(dsl.fetchValue(Routines.leafTerminalBoundaryId(
                runId, leafOrdinal(runId, leafName)).isNull()));
    }


    private Long[] lastKey(Table<?> leaf) {
        Field<Long> id = leaf.field("id", Long.class);
        Field<Integer> bucket = leaf.field("bucket", Integer.class);
        Record row = dsl.select(id, bucket)
                .from(leaf)
                .orderBy(id.desc(), bucket.desc())
                .limit(1)
                .fetchOne();
        if (row == null) {
            return null;
        }
        return new Long[]{row.get(id), row.get(bucket).longValue()};
    }

    private long leafBlocks(String leafName) {
        return dsl.fetchOne("SELECT pg_catalog.pg_relation_size(?::regclass)"
                                + " / pg_catalog.current_setting('block_size')::bigint",
                        PARTITION_SCHEMA + "." + leafName)
                .get(0, Long.class);
    }
}
