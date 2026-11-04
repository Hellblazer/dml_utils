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
 * Verifies {@code dml_utils_lib.build_leaf_boundary_population_sql}: the returned
 * statement scans one named leaf in boundary-key order and appends its chunk
 * starts after the boundaries already committed for the run, tagged with the
 * leaf ordinal. Bad inputs are rejected with {@code 22023}.
 */
class BuildLeafBoundaryPopulationSqlTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String LOW = PARTITION_SCHEMA + ".test_partitioned_range_low";
    private static final String HIGH = PARTITION_SCHEMA + ".test_partitioned_range_high";

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
     * chunk with the requested leaf ordinal and no per-leaf terminal.
     */
    @Test
    void insertsOneStartPerChunkForTheLeaf() {
        insertRows(0L, 1L, 2L, 3L, 4L);
        long runId = populatePartitionWise("leaf-builder-primary_key", "test_partitioned_range", TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        String sql = Routines.buildLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, 2L, 1);

        assertTrue(sql.contains("INSERT INTO dml_utils_data.migration_boundary"),
                "the statement inserts boundaries");
        assertTrue(sql.contains(LOW), "the statement scans the requested leaf");

        dsl.execute(sql);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(3, boundaries.size(), "five rows at chunk size two = three starts");
        for (int i = 0; i < boundaries.size(); i++) {
            assertEquals(i, boundaries.get(i).getBoundaryNo().intValue());
            assertEquals(1, boundaries.get(i).getLeafOrdinal());
        }
        assertEquals(0L, boundaries.getFirst().getBoundaryId().getBigintValues()[0],
                "the first start is the leaf minimum");
        assertEquals(4L, boundaries.getLast().getBoundaryId().getBigintValues()[0],
                "the last start is the final chunk's first row");
    }

    /**
     * A later leaf appends after every boundary already committed for the run, so
     * the flattened boundary numbers stay contiguous and retain each leaf's tag.
     */
    @Test
    void appendsAfterTheAlreadyCommittedBoundaries() {
        insertRows(0L, 1L, 200L, 201L);
        long runId = populatePartitionWise("leaf-builder-primary_key", "test_partitioned_range", TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        int highOrdinal = leafOrdinal(runId, "test_partitioned_range_high");

        dsl.execute(Routines.buildLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, 1L, 1));
        dsl.execute(Routines.buildLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range_high",
                new String[]{"id"}, new String[]{"bigint"}, runId, 1L, highOrdinal));

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(List.of(0L, 1L, 2L, 3L),
                boundaries.stream().map(MigrationBoundaryRecord::getBoundaryNo).toList());
        assertEquals(List.of(1, 1, highOrdinal, highOrdinal),
                boundaries.stream().map(MigrationBoundaryRecord::getLeafOrdinal).toList());
        assertEquals(200L, boundaries.get(2).getBoundaryId().getBigintValues()[0],
                "the high leaf starts at its own minimum");
    }

    /**
     * An empty leaf contributes no boundaries; the builder still returns valid SQL.
     */
    @Test
    void insertsNothingForAnEmptyLeaf() {
        long runId = populatePartitionWise("leaf-builder-primary_key", "test_partitioned_range", TEMPLATE, ChunkingStrategy.primary_key, 1, null);

        dsl.execute(Routines.buildLeafBoundaryPopulationSql(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, 1L, 1));

        assertEquals(0, boundaries(runId).size());
    }

    /**
     * A NULL run id, a non-positive chunk size, a leaf ordinal below one,
     * mismatched key arrays and an unsupported kind are rejected.
     */
    @Test
    void rejectsBadInputs() {
        long runId = populatePartitionWise("leaf-builder-primary_key", "test_partitioned_range", TEMPLATE, ChunkingStrategy.primary_key, 1, null);

        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, null, 1L, 1));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, null, 1));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, 0L, 1));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, -1L, 1));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, 1L, null));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"bigint"}, runId, 1L, 0));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id", "id2"}, new String[]{"bigint"}, runId, 1L, 1));
        assertSqlState("22023", () -> Routines.buildLeafBoundaryPopulationSql(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range_low",
                new String[]{"id"}, new String[]{"integer"}, runId, 1L, 1));
    }


    private void insertRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_PARTITIONED_RANGE,
                            TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(id, "payload-" + id)
                    .execute();
        }
    }


}
