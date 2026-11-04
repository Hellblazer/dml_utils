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
 * Verifies {@code dml_utils_lib.leaf_boundary_population_sql}: the wrapper builds
 * the same blocks, ordered-key and primary-key leaf start statements the
 * calculation routine executes, and executing each appends the expected starts.
 */
class LeafBoundaryPopulationSqlTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String TABLE = "test_partitioned_range";
    private static final String ORDERED_INDEX = "test_partitioned_range_ordered_key_idx";

    /**
     * Empties the fixture and migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A primary-key leaf statement inserts one start per chunk.
     */
    @Test
    void buildsAPrimaryKeyLeafStatement() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-primary_key", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        execute(runId, ChunkingStrategy.primary_key, 2, 1);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(3, boundaries.size());
        assertEquals(0L, boundaries.getFirst().getBoundaryId().getBigintValues()[0]);
        assertEquals(4L, boundaries.getLast().getBoundaryId().getBigintValues()[0]);
        assertTrue(boundaries.stream().allMatch(b -> b.getLeafOrdinal() == 1));
    }

    /**
     * An ordered-key leaf statement uses the stored key descriptor.
     */
    @Test
    void buildsAnOrderedKeyLeafStatement() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-ordered_key", TABLE, TEMPLATE, ChunkingStrategy.ordered_key, 2, ORDERED_INDEX);

        execute(runId, ChunkingStrategy.ordered_key, 2, 1);

        assertEquals(3, boundaries(runId).size());
    }

    /**
     * A blocks leaf statement inserts one start per block chunk.
     */
    @Test
    void buildsABlocksLeafStatement() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-blocks", TABLE, TEMPLATE, ChunkingStrategy.blocks, 1, null);

        String sql = Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.blocks, 1, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, 1);
        assertTrue(sql.contains("pg_relation_size"));
        dsl.execute(sql);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertTrue(boundaries.size() >= 1);
        assertEquals(0L, boundaries.getFirst().getBoundaryId().getBigintValues()[0]);
        assertTrue(boundaries.stream().allMatch(b -> b.getLeafOrdinal() == 1));
    }


    private void execute(long runId, ChunkingStrategy chunkBy, int chunkSize, int ordinal) {
        dsl.execute(Routines.leafBoundaryPopulationSql(dsl.configuration(), chunkBy,
                chunkSize, PARTITION_SCHEMA, "test_partitioned_range_low", runId, ordinal));
    }


}
