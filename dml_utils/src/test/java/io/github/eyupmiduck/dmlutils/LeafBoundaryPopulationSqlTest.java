package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /**
     * Empties the fixture and migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndRange();
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
     * An ordered-key leaf statement uses the stored key descriptor, not the
     * primary key: the run's index is on (payload, id), so the rendered statement
     * must mention the payload column that the primary key does not have.
     */
    @Test
    void buildsAnOrderedKeyLeafStatement() throws Exception {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        String index = "test_partitioned_range_leaf_descriptor_idx";
        try {
            // Ordered-key columns must be NOT NULL; the DDL is inside the try so
            // the finally always restores both catalog changes.
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
            executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + TABLE
                    + " ALTER COLUMN payload SET NOT NULL");
            executeAsOwner("CREATE UNIQUE INDEX " + index + " ON " + PARTITION_SCHEMA
                    + "." + TABLE + " (payload, id)");
            long runId = populatePartitionWise("leaf-builder-wrapper-ordered_key", TABLE,
                    TEMPLATE, ChunkingStrategy.ordered_key, 2, index);

            String sql = Routines.leafBoundaryPopulationSql(dsl.configuration(),
                    ChunkingStrategy.ordered_key, 2, PARTITION_SCHEMA,
                    "test_partitioned_range_low", runId, 1);
            assertTrue(sql.contains("payload"),
                    "the statement uses the run's stored ordered-key descriptor");
            dsl.execute(sql);

            assertEquals(3, boundaries(runId).size());
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
            executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + TABLE
                    + " ALTER COLUMN payload DROP NOT NULL");
        }
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
        assertEquals(1, boundaries.size(),
                "the seeded low leaf is one block at chunk size one");
        assertEquals(0L, boundaries.getFirst().getBoundaryId().getBigintValues()[0],
                "the single start is block 0");
        assertTrue(boundaries.stream().allMatch(b -> b.getLeafOrdinal() == 1));
    }


    /**
     * The argument must match the run's stored strategy: dispatching on a
     * differing argument would render a statement for a strategy the run does not
     * chunk by.
     */
    @Test
    void rejectsAMismatchedStrategy() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-mismatch", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);

        assertSqlState("22023", () -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.blocks, 2, PARTITION_SCHEMA, "test_partitioned_range_low",
                runId, 1));
    }

    /**
     * A NULL strategy used to fall through to the primary-key builder; it is now
     * rejected instead of silently rendering a statement for one strategy.
     */
    @Test
    void rejectsANullStrategy() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-null", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);

        assertSqlState("22023", () -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                null, 2, PARTITION_SCHEMA, "test_partitioned_range_low", runId, 1));
    }

    /**
     * The argument domains reject NULL/zero/negative sizes and ordinals, a NULL
     * run id, and a NULL or blank leaf schema/name before any statement is built.
     */
    @Test
    void rejectsNullAndOutOfRangeArguments() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-args", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);

        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, null, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 0, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, -1, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, PARTITION_SCHEMA,
                "test_partitioned_range_low", (Long) null, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, null));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, 0));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, null,
                "test_partitioned_range_low", runId, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, "   ",
                "test_partitioned_range_low", runId, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, PARTITION_SCHEMA, null, runId, 1));
        assertDomainViolation(() -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 2, PARTITION_SCHEMA, "   ", runId, 1));
    }

    /**
     * A chunk size that differs from the run's stored size would build boundaries
     * with a stride the run does not advertise.
     */
    @Test
    void rejectsAMismatchedChunkSize() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-wrapper-size", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);

        assertSqlState("22023", () -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.primary_key, 3, PARTITION_SCHEMA,
                "test_partitioned_range_low", runId, 1));
    }

    /**
     * An unknown run id raises {@code P0002}, including the blocks path that now
     * reads the run row to decide the strategy.
     */
    @Test
    void rejectsAnUnknownRun() {
        assertSqlState("P0002", () -> Routines.leafBoundaryPopulationSql(dsl.configuration(),
                ChunkingStrategy.blocks, 2, PARTITION_SCHEMA, "test_partitioned_range_low",
                0L, 1));
    }

    private void execute(long runId, ChunkingStrategy chunkBy, int chunkSize, int ordinal) {
        dsl.execute(Routines.leafBoundaryPopulationSql(dsl.configuration(), chunkBy,
                chunkSize, PARTITION_SCHEMA, "test_partitioned_range_low", runId, ordinal));
    }


}
