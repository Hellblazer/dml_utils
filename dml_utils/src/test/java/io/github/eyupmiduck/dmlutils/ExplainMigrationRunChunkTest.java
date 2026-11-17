package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.ExplainMigrationRunChunkRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationBoundariesRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.NextMigrationChunk;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.PrepareChunkSqlRender;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.MigrationRunBoundaryKeyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.explain_migration_run_chunk}: it explains a real
 * stored chunk by run id and boundary number, renders the same SQL the runner
 * would execute for primary-key, ordered-key, block, composite and
 * partition-wise runs, identifies the leaf, and rejects terminal, missing and
 * changed-tree boundaries.
 */
class ExplainMigrationRunChunkTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_partitioned_range";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";
    private static final String ORDERED_KEY_INDEX = "test_ordered_key_created_at_id_idx";
    private static final String PARTITIONED_ORDERED_KEY_INDEX =
            "test_partitioned_range_ordered_key_idx";

    /**
     * Resets the migration metadata and the driving fixtures between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_ORDERED_KEY).execute();
        dsl.truncate(TEST_COMPOSITE_PK).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A primary-key chunk is rendered as the runner would render it and reports
     * no leaf.
     */
    @Test
    void explainsAPrimaryKeyChunk() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = populate(TEST_BIGINT, uniqueLabel("explain-run-pk"),
                ChunkingStrategy.primary_key, null, false, 2);

        ExplainMigrationRunChunkRecord plan = explain(runId, 0L);

        assertEquals("chunk_non_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, 0L), plan.getOSqlText(),
                "the explained SQL matches the coordinator's render");
        assertTrue(plan.getOSqlText().contains("UPDATE "),
                "the stored template is rendered");
        assertNotNull(plan.getOPlan(), "the row carries the JSON plan");
        assertEquals("ModifyTable", planNodeType(plan.getOPlan()));
        assertNull(plan.getOLeafSchemaName(), "a single-relation chunk has no leaf");
        assertNull(plan.getOLeafName());
        assertNull(plan.getOLeafOrdinal());
    }

    /**
     * The final chunk is identified as such and uses the inclusive upper bound.
     */
    @Test
    void explainsTheFinalChunk() {
        seedBigint(1, 2, 3, 4, 5, 6);
        long runId = populate(TEST_BIGINT, uniqueLabel("explain-run-final"),
                ChunkingStrategy.primary_key, null, false, 2);
        long finalChunk = migrationRun(runId).getTotalChunks() - 1;

        ExplainMigrationRunChunkRecord plan = explain(runId, finalChunk);

        assertEquals("chunk_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, finalChunk), plan.getOSqlText(),
                "the final chunk matches the coordinator's inclusive render");
        assertTrue(plan.getOSqlText().contains("<="),
                () -> "the final chunk uses an inclusive upper bound: " + plan.getOSqlText());
    }

    /**
     * An ordered-key chunk uses the run's stored key descriptor and renders
     * timestamptz/bigint literals.
     */
    @Test
    void explainsAnOrderedKeyChunk() {
        seedOrderedKey();
        long runId = populate(TEST_ORDERED_KEY, uniqueLabel("explain-run-ordered"),
                ChunkingStrategy.ordered_key, ORDERED_KEY_INDEX, false, 2);

        ExplainMigrationRunChunkRecord plan = explain(runId, 0L);

        assertEquals("chunk_non_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, 0L), plan.getOSqlText());
        assertTrue(plan.getOSqlText().contains("::timestamptz"),
                "the ordered key casts its timestamptz column");
        assertNotNull(plan.getOPlan());
    }

    /**
     * A block chunk is rendered as a half-open ctid range and reported as a
     * non-final chunk, since blocks have no inclusive distinction.
     */
    @Test
    void explainsABlockChunk() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = populate(TEST_BIGINT, uniqueLabel("explain-run-blocks"),
                ChunkingStrategy.blocks, null, false, 1);

        ExplainMigrationRunChunkRecord plan = explain(runId, 0L);

        assertEquals("chunk_non_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, 0L), plan.getOSqlText());
        assertTrue(plan.getOSqlText().contains("ctid"),
                "a block chunk filters on the physical ctid range");
        assertNotNull(plan.getOPlan());
    }

    /**
     * A partition-wise block chunk is rendered against its manifest leaf as a
     * half-open ctid range, with the leaf's own block snapshot.
     */
    @Test
    void explainsAPartitionWiseBlockChunk() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        long runId = populate(TEST_PARTITIONED_RANGE, uniqueLabel("explain-run-partition-blocks"),
                ChunkingStrategy.blocks, null, true, 1);
        calculateRun(runId);

        ExplainMigrationRunChunkRecord plan = explain(runId, 0L);

        assertEquals("chunk_non_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, 0L), plan.getOSqlText(),
                "the partition-wise block chunk matches the coordinator's render");
        assertEquals(PARTITION_SCHEMA, plan.getOLeafSchemaName());
        assertEquals("test_partitioned_range_low", plan.getOLeafName());
        assertEquals(1, plan.getOLeafOrdinal());
        assertTrue(plan.getOSqlText().contains("ctid"),
                "a block chunk filters on the physical ctid range");
        assertTrue(plan.getOSqlText().contains("test_partitioned_range_low"),
                "the SQL targets the manifest leaf, not the parent");
    }

    /**
     * A composite key renders both key columns and matches the runner's
     * prefix-equality predicate.
     */
    @Test
    void explainsACompositeKeyChunk() {
        dsl.insertInto(TEST_COMPOSITE_PK, TEST_COMPOSITE_PK.A, TEST_COMPOSITE_PK.B)
                .values(1L, 1L).values(1L, 2L).values(2L, 1L).values(2L, 2L).values(2L, 3L)
                .execute();
        long runId = populate(TEST_COMPOSITE_PK, uniqueLabel("explain-run-composite"),
                ChunkingStrategy.primary_key, null, false, 2);

        ExplainMigrationRunChunkRecord plan = explain(runId, 0L);

        assertEquals("chunk_non_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, 0L), plan.getOSqlText(),
                "the composite-key predicate matches the runner");
        assertTrue(plan.getOSqlText().contains("t.a"),
                "the first key column is qualified and quoted");
        assertTrue(plan.getOSqlText().contains("t.b"),
                "the second key column is qualified and quoted");
    }

    /**
     * A partition-wise ordered-key chunk explains against its manifest leaf and
     * reports the leaf identity.
     */
    @Test
    void explainsAPartitionWiseChunk() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        long runId = populate(TEST_PARTITIONED_RANGE, uniqueLabel("explain-run-partition"),
                ChunkingStrategy.ordered_key, PARTITIONED_ORDERED_KEY_INDEX, true, 2);
        calculateRun(runId);

        ExplainMigrationRunChunkRecord plan = explain(runId, 0L);

        assertEquals("chunk_non_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, 0L), plan.getOSqlText(),
                "the leaf chunk matches the coordinator's render");
        assertEquals(PARTITION_SCHEMA, plan.getOLeafSchemaName());
        assertEquals("test_partitioned_range_low", plan.getOLeafName());
        assertEquals(1, plan.getOLeafOrdinal());
        assertTrue(plan.getOSqlText().contains("test_partitioned_range_low"),
                "the SQL targets the manifest leaf, not the parent");
    }

    /**
     * The last chunk of a leaf uses the manifest high-water snapshot and the
     * inclusive bound, exactly as the runner does.
     */
    @Test
    void explainsTheLastChunkOfAPartitionWiseLeaf() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        long runId = populate(TEST_PARTITIONED_RANGE, uniqueLabel("explain-run-leaf-final"),
                ChunkingStrategy.primary_key, null, true, 2);
        calculateRun(runId);

        int firstLeaf = Routines.migrationBoundaries(dsl.configuration(), runId).getFirst()
                .getLeafOrdinal();
        long lastChunkOfFirstLeaf = Routines.migrationBoundaries(dsl.configuration(), runId)
                .stream()
                .filter(boundary -> boundary.getLeafOrdinal() == firstLeaf)
                .mapToLong(MigrationBoundariesRecord::getBoundaryNo)
                .max()
                .orElseThrow();

        ExplainMigrationRunChunkRecord plan = explain(runId, lastChunkOfFirstLeaf);

        assertEquals("chunk_final", plan.getOPlanKind());
        assertEquals(runnerSql(runId, lastChunkOfFirstLeaf), plan.getOSqlText(),
                "the leaf-final chunk matches the runner's inclusive render");
        assertEquals(firstLeaf, plan.getOLeafOrdinal());
    }

    /**
     * The terminal boundary is the run's high-water mark, not a chunk, and is
     * rejected.
     */
    @Test
    void rejectsTheTerminalBoundary() {
        seedBigint(1, 2, 3);
        long runId = populate(TEST_BIGINT, uniqueLabel("explain-run-terminal"),
                ChunkingStrategy.primary_key, null, false, 2);

        assertSqlState("22023", () -> explain(runId, migrationRun(runId).getTotalChunks()));
    }

    /**
     * A boundary number the run does not have is rejected as missing.
     */
    @Test
    void rejectsAMissingBoundary() {
        seedBigint(1, 2, 3);
        long runId = populate(TEST_BIGINT, uniqueLabel("explain-run-no-boundary"),
                ChunkingStrategy.primary_key, null, false, 2);

        assertSqlState("P0002", () -> explain(runId, 9999L));
    }

    /**
     * A run id that does not exist is rejected as missing.
     */
    @Test
    void rejectsAMissingRun() {
        assertSqlState("P0002", () -> explain(999_999L, 0L));
    }

    /**
     * A NULL run id or boundary number is rejected before any lookup.
     */
    @Test
    void rejectsNullArguments() {
        assertDomainViolation(() -> Routines.explainMigrationRunChunk(
                dsl.configuration(), null, 0L));
        assertDomainViolation(() -> Routines.explainMigrationRunChunk(
                dsl.configuration(), 1L, null));
    }

    /**
     * A partition tree changed after calculation fails closed before a plan is
     * produced.
     */
    @Test
    void failsClosedWhenThePartitionTreeChanged() throws Exception {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        long runId = populate(TEST_PARTITIONED_RANGE, uniqueLabel("explain-run-changed"),
                ChunkingStrategy.primary_key, null, true, 2);
        calculateRun(runId);
        try {
            executeAsOwner("ALTER TABLE " + SCHEMA + "." + TABLE
                    + " DETACH PARTITION " + SCHEMA + ".test_partitioned_range_mid");
            assertSqlState("55000", () -> explain(runId, 0L));
        } finally {
            restoreMidPartition();
        }
    }

    /**
     * Creates a run over the fixture and returns its id.
     *
     * @param table         the driving table
     * @param label         the run label
     * @param chunkBy       the chunking strategy
     * @param boundaryIndex the ordered-key index, or null
     * @param partitionWise whether the run is partition-wise
     * @param chunkSize     the chunk size
     * @return the run id
     */
    private long populate(org.jooq.Table<?> table, String label, ChunkingStrategy chunkBy,
                          String boundaryIndex, boolean partitionWise, int chunkSize) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(),
                        table.getSchema().getName(), table.getName(), label, TEMPLATE,
                        chunkSize, 1, chunkBy, "t", 0, boundaryIndex, partitionWise, 3, 1000);
    }


    /**
     * Reads the plan for one stored boundary.
     *
     * @param runId      the run id
     * @param boundaryNo the boundary number
     * @return the single plan row
     */
    private ExplainMigrationRunChunkRecord explain(long runId, long boundaryNo) {
        List<ExplainMigrationRunChunkRecord> plans = Routines.explainMigrationRunChunk(
                dsl.configuration(), runId, boundaryNo);
        assertEquals(1, plans.size(), "one plan row per chunk");
        return plans.getFirst();
    }

    /**
     * Renders the SQL the coordinator would execute for a boundary, using the
     * runner's own selector ({@code next_migration_chunk}) and renderer.
     *
     * @param runId      the run id
     * @param boundaryNo the target boundary
     * @return the expected chunk SQL
     */
    private String runnerSql(long runId, long boundaryNo) {
        MigrationRunRecord run = migrationRun(runId);
        String[] keyColumns = null;
        String[] keyKinds;
        if (run.getChunkBy() == ChunkingStrategy.blocks) {
            keyKinds = new String[]{"bigint"};
        } else {
            MigrationRunBoundaryKeyRecord key =
                    io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                            .migrationRunBoundaryKey(dsl.configuration(), runId).getFirst();
            keyColumns = key.getOKeyColumns();
            keyKinds = key.getOKeyKinds();
        }
        Long[] earlierBoundaries = dsl.select(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.lt(boundaryNo))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .toArray(Long[]::new);

        NextMigrationChunk chunk = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .nextMigrationChunk(dsl.configuration(), runId, run.getPartitionWise(),
                        keyKinds, earlierBoundaries, run.getDrivingTableSchemaName(),
                        run.getDrivingTableName());
        assertEquals(boundaryNo, chunk.getOBoundaryNo().longValue(),
                "the runner selector resolves the requested boundary");

        if (run.getChunkBy() == ChunkingStrategy.blocks) {
            return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                    .renderBlockChunkSql(dsl.configuration(), run.getSqlText(),
                            chunk.getOChunkSchemaName(), chunk.getOChunkTableName(),
                            run.getDrivingTableAlias(),
                            Long.parseLong(chunk.getOStartValues()[0]),
                            Long.parseLong(chunk.getOEndValues()[0]));
        }
        PrepareChunkSqlRender prepared = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .prepareChunkSqlRender(dsl.configuration(), run.getSqlText(),
                        run.getDrivingTableSchemaName(), run.getDrivingTableName(),
                        run.getDrivingTableAlias(), keyColumns, keyKinds);
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .renderPreparedChunkSql(dsl.configuration(),
                        prepared.getOTemplate(),
                        chunk.getOChunkSchemaName(), chunk.getOChunkTableName(),
                        run.getDrivingTableAlias(), prepared.getOColumnNames(),
                        keyKinds, prepared.getOKeyCollations(),
                        chunk.getOStartValues(), chunk.getOEndValues(),
                        chunk.getOIsFinal());
    }

}
