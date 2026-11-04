package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.build_block_leaf_boundary_population_sql}: the
 * returned statement inserts one block-chunk start per {@code i_chunk_size} blocks
 * of the named leaf, appends them after the run's existing boundaries and tags
 * them with the leaf ordinal. Bad inputs are rejected with {@code 22023}.
 */
class BuildBlockLeafBoundaryPopulationSqlTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String LOW = "test_partitioned_range_low";
    private static final String HIGH = "test_partitioned_range_high";

    /**
     * Empties the range fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * The statement names the leaf and, when executed, inserts one start per
     * chunk of blocks with the requested leaf ordinal and no per-leaf terminal.
     */
    @Test
    void insertsOneStartPerBlockChunkForTheLeaf() {
        insertManyRows(0, 100);
        long blockCount = leafBlocks(LOW);
        assertTrue(blockCount > 1, "the seeded leaf should span several blocks");
        long runId = populatePartitionWise("block-leaf-builder", "test_partitioned_range", TEMPLATE, ChunkingStrategy.blocks, 1, null);

        String sql = Routines.buildBlockLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, LOW, runId, 1L, 1);

        assertTrue(sql.contains("INSERT INTO dml_utils_data.migration_boundary"));
        assertTrue(sql.contains(PARTITION_SCHEMA + "." + LOW));

        dsl.execute(sql);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(blockCount, boundaries.size(), "one start per block at chunk size one");
        for (int i = 0; i < boundaries.size(); i++) {
            assertEquals(i, boundaries.get(i).getBoundaryNo().intValue());
            assertEquals(1, boundaries.get(i).getLeafOrdinal());
            assertEquals(i, boundaries.get(i).getBoundaryId().getBigintValues()[0].intValue(),
                    "start i is block i");
        }
    }

    /**
     * A later leaf appends after every boundary already committed for the run.
     */
    @Test
    void appendsAfterTheAlreadyCommittedBoundaries() {
        insertManyRows(0, 50);
        insertManyRows(200, 50);
        long lowBlocks = leafBlocks(LOW);
        long highBlocks = leafBlocks(HIGH);
        long runId = populatePartitionWise("block-leaf-builder", "test_partitioned_range", TEMPLATE, ChunkingStrategy.blocks, 2, null);
        int highOrdinal = leafOrdinal(runId, HIGH);

        dsl.execute(Routines.buildBlockLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, LOW, runId, 2L, 1));
        dsl.execute(Routines.buildBlockLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, HIGH, runId, 2L, highOrdinal));

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        long lowStarts = (lowBlocks + 1) / 2;
        long highStarts = (highBlocks + 1) / 2;
        assertEquals(lowStarts + highStarts, boundaries.size());
        for (int i = 0; i < lowStarts; i++) {
            assertEquals(i, boundaries.get(i).getBoundaryNo().intValue());
            assertEquals(1, boundaries.get(i).getLeafOrdinal());
        }
        for (int i = 0; i < highStarts; i++) {
            MigrationBoundaryRecord boundary = boundaries.get((int) (lowStarts + i));
            assertEquals(lowStarts + i, boundary.getBoundaryNo().longValue());
            assertEquals(highOrdinal, boundary.getLeafOrdinal());
            assertEquals(i * 2L, boundary.getBoundaryId().getBigintValues()[0].longValue());
        }
    }

    /**
     * An empty leaf contributes no boundaries.
     */
    @Test
    void insertsNothingForAnEmptyLeaf() {
        long runId = populatePartitionWise("block-leaf-builder", "test_partitioned_range", TEMPLATE, ChunkingStrategy.blocks, 1, null);

        dsl.execute(Routines.buildBlockLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, LOW, runId, 1L, 1));

        assertEquals(0, boundaries(runId).size());
    }

    /**
     * A NULL run id, a non-positive chunk size and a leaf ordinal below one are
     * rejected.
     */
    @Test
    void rejectsBadInputs() {
        long runId = populatePartitionWise("block-leaf-builder", "test_partitioned_range", TEMPLATE, ChunkingStrategy.blocks, 1, null);

        assertSqlState("22023", () -> Routines.buildBlockLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, LOW, null, 1L, 1));
        assertSqlState("22023", () -> Routines.buildBlockLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, LOW, runId, null, 1));
        assertSqlState("22023", () -> Routines.buildBlockLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, LOW, runId, 0L, 1));
        assertSqlState("22023", () -> Routines.buildBlockLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, LOW, runId, -1L, 1));
        assertSqlState("22023", () -> Routines.buildBlockLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, LOW, runId, 1L, null));
        assertSqlState("22023", () -> Routines.buildBlockLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, LOW, runId, 1L, 0));
    }


    private void insertManyRows(int firstId, int count) {
        dsl.execute("INSERT INTO " + PARTITION_SCHEMA + ".test_partitioned_range"
                        + " (id, payload) SELECT g, repeat('x', 200)"
                        + " FROM generate_series(?, ?) AS g",
                firstId, firstId + count - 1);
    }

    private long leafBlocks(String leafName) {
        return dsl.fetchOne(
                        "SELECT pg_catalog.pg_relation_size(?::regclass)"
                                + " / pg_catalog.current_setting('block_size')::bigint",
                        PARTITION_SCHEMA + "." + leafName)
                .get(0, Long.class);
    }


}
