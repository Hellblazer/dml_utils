package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.LeafPartitionsRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.ChunksFixture.PARTITIONED;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.PARTITIONED_ORDERED_INDEX;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.PLAIN;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.PLAIN_ORDERED_INDEX;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.ROWS;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.ROW_EXPRESSION;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.SCHEMA;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestChunksFour.TEST_CHUNKS_FOUR;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestChunksPartitioned.TEST_CHUNKS_PARTITIONED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a primary key or ordered key of four columns, the supported maximum,
 * with the shape of nexus.chunks: (tenant_id text, collection text, chash bytea,
 * embedding_model text). The bytea column is third in the primary key and last
 * in the ordered key. Every row is processed exactly once on a plain table and
 * on a table partitioned two levels (by model, then by tenant), through the
 * primary-key and ordered-key strategies, with the rendered range predicate
 * agreeing with the boundaries, and a failed run resumes without repeating
 * a completed chunk.
 */
class FourColumnKeyTest extends ChunkRenderingTestBase {

    private static final String APPEND =
            "UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x' WHERE <chunking_clause>";

    /**
     * Fails on one row deep in the key order, so earlier chunks have completed.
     */
    private static final String FAIL_ON_ONE_ROW = "UPDATE <driving_table> SET payload = CASE"
            + " WHEN t.tenant_id = 't1' AND t.collection = 'b' AND t.embedding_model = 'm2'"
            + " AND t.chash = pg_catalog.decode('00ff', 'hex')"
            + " THEN (pg_catalog.get_byte(t.chash, 0) / 0)::text ELSE 'x' END WHERE <chunking_clause>";

    private static final String[] PRIMARY_KINDS = {"text", "text", "bytea", "text"};
    private static final String PRIMARY_COLUMNS = "tenant_id, collection, chash, embedding_model";
    private static final String[] ORDERED_KINDS = {"text", "text", "text", "bytea"};
    private static final String ORDERED_COLUMNS = "embedding_model, tenant_id, collection, chash";

    /**
     * Seeds both fixtures, so a test works on a table with a known population.
     */
    @BeforeEach
    void seed() {
        dsl.truncate(TEST_CHUNKS_FOUR).execute();
        dsl.truncate(TEST_CHUNKS_PARTITIONED).execute();
        ChunksFixture.seed(dsl, PLAIN);
        ChunksFixture.seed(dsl, PARTITIONED);
    }

    /**
     * Drops the helper function a test may have created.
     */
    @AfterEach
    void dropHelperFunction() {
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_chunk(text, text, bytea, text)");
    }

    /**
     * The fixtures hold the rows the other tests count on.
     */
    @Test
    void theFixturesAreSeeded() {
        assertEquals(ROWS, dsl.fetchCount(TEST_CHUNKS_FOUR), "plain table rows");
        assertEquals(ROWS, dsl.fetchCount(TEST_CHUNKS_PARTITIONED), "partitioned table rows");
        assertEquals(ROWS / 2, dsl.fetchOne("SELECT count(*) FROM " + SCHEMA
                + ".test_chunks_partitioned_m1").get(0, Integer.class), "rows under model m1");
    }

    /**
     * A primary-key run over the four-column key processes every row exactly once
     * at chunk sizes that put boundaries inside a tenant, inside a collection and
     * between hashes, with one worker and with several.
     */
    @Test
    void processesAFourColumnPrimaryKeyExactlyOnce() {
        for (int[] run : new int[][]{{5, 1}, {64, 3}, {ROWS, 1}, {ROWS * 4, 2}}) {
            dsl.update(TEST_CHUNKS_FOUR).set(TEST_CHUNKS_FOUR.PAYLOAD, (String) null).execute();
            String label = uniqueLabel("four-primary-" + run[0]);

            Routines.runMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PLAIN, label,
                    run[0], run[1], "t", ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

            assertEveryRowDoneOnce(TEST_CHUNKS_FOUR, "chunk size " + run[0]);
            assertTrue(runCompleted(label), "the run completes at chunk size " + run[0]);
        }
    }

    /**
     * An ordered-key run over a four-column unique index, bytea last, records the
     * index, its columns in index order and the kinds, and processes every row
     * exactly once.
     */
    @Test
    void processesAFourColumnOrderedKeyExactlyOnce() {
        String label = uniqueLabel("four-ordered");

        Routines.runMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PLAIN, label, 7, 2, "t",
                ChunkingStrategy.ordered_key, 1, 0, PLAIN_ORDERED_INDEX, false, 3, 1000);

        assertEveryRowDoneOnce(TEST_CHUNKS_FOUR, "ordered key");
        assertTrue(runCompleted(label), "the ordered-key run completes");
        var run = dsl.selectFrom(MIGRATION_RUN).where(MIGRATION_RUN.LABEL.eq(label)).fetchOne();
        assertEquals(PLAIN_ORDERED_INDEX, run.getOrderedKeyIndex());
        assertArrayEquals(new String[]{"embedding_model", "tenant_id", "collection", "chash"},
                run.getOrderedKeyColumns(), "the key columns in index order");
        assertArrayEquals(ORDERED_KINDS, run.getOrderedKeyKinds(), "the key kinds, bytea last");
    }

    /**
     * Every row of the table falls in exactly one rendered chunk, for the primary
     * key (bytea third) and for the ordered key (bytea last): the rendered range
     * predicate tiles the same row order the boundaries were cut in.
     */
    @Test
    void everyRowLandsInExactlyOneRenderedChunk() {
        List<String> expected = ChunksFixture.allRows(dsl, PLAIN);
        assertEquals(ROWS, expected.size());

        for (int chunkSize : new int[]{1, 7, 64}) {
            long primary = populate(PLAIN, "four-render-primary", chunkSize,
                    ChunkingStrategy.primary_key, null);
            assertEquals(expected, sorted(renderedChunkRows(primary, TEST_CHUNKS_FOUR,
                            PRIMARY_COLUMNS, ROW_EXPRESSION, PRIMARY_KINDS, dsl)),
                    "primary key, chunk size " + chunkSize + ": each row in exactly one chunk");

            long ordered = populate(PLAIN, "four-render-ordered", chunkSize,
                    ChunkingStrategy.ordered_key, PLAIN_ORDERED_INDEX);
            assertEquals(expected, sorted(renderedChunkRows(ordered, TEST_CHUNKS_FOUR,
                            ORDERED_COLUMNS, ROW_EXPRESSION, ORDERED_KINDS, dsl)),
                    "ordered key, chunk size " + chunkSize + ": each row in exactly one chunk");
        }
    }

    /**
     * A function taking the four key columns' types runs over the table, because
     * the resolver accepts a four-column key and exposes each column's real type.
     */
    @Test
    void appliesAFunctionOverAFourColumnPrimaryKey() {
        dsl.execute("CREATE OR REPLACE FUNCTION public.mark_chunk(tenant text, coll text, h bytea,"
                + " model text) RETURNS void LANGUAGE sql AS $$"
                + " UPDATE dml_utils_fixtures.test_chunks_four"
                + " SET payload = coalesce(payload, '') || 'x'"
                + " WHERE tenant_id = tenant AND collection = coll AND chash = h"
                + " AND embedding_model = model $$");
        String label = uniqueLabel("four-function");

        Routines.runFunctionOverTable(dsl.configuration(), SCHEMA, PLAIN, "public", "mark_chunk",
                50, 2, label, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertTrue(runCompleted(label), "the run completes");
        assertEveryRowDoneOnce(TEST_CHUNKS_FOUR, "function over table");
    }

    /**
     * The explain entry point plans a representative chunk over the four-column
     * key, with the synthetic bytea range in the third position.
     */
    @Test
    void explainsAChunkOverAFourColumnKey() {
        var plans = Routines.explainMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PLAIN, 10,
                "t", ChunkingStrategy.primary_key, null, false, null);

        assertEquals(List.of("boundary_population", "chunk_non_final", "chunk_final"),
                plans.stream().map(plan -> plan.getOPlanKind()).toList());
        assertTrue(plans.get(1).getOSqlText().contains("pg_catalog.decode('00', 'hex')"),
                "the synthetic chunk carries the bytea range: " + plans.get(1).getOSqlText());
        assertTrue(plans.get(1).getOSqlText().contains("embedding_model"),
                "the synthetic chunk reaches the fourth key column");
    }

    /**
     * The leaves of a table partitioned two levels are the storage partitions
     * only, in the deterministic manifest order, so a partition-wise run sees the
     * default leaves as well.
     */
    @Test
    void enumeratesTheLeavesOfATwoLevelTree() {
        List<LeafPartitionsRecord> leaves =
                io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.leafPartitions(
                        dsl.configuration(), SCHEMA, PARTITIONED, ChunkingStrategy.primary_key);

        assertEquals(ChunksFixture.PARTITIONED_LEAVES,
                leaves.stream().map(LeafPartitionsRecord::getLeafName).toList(),
                "six storage leaves, none of the first-level partitions");
    }

    /**
     * A partition-wise primary-key run over the two-level tree processes every
     * row exactly once, snapshots every leaf in the manifest and records the run
     * as partition-wise.
     */
    @Test
    void processesATwoLevelPartitionedTablePartitionWise() {
        String label = uniqueLabel("four-partition-wise");

        Routines.runMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PARTITIONED, label, 9, 2,
                "t", ChunkingStrategy.primary_key, 1, 0, null, true, 3, 1000);

        assertEveryRowDoneOnce(TEST_CHUNKS_PARTITIONED, "partition-wise primary key");
        assertTrue(runCompleted(label), "the partition-wise run completes");
        long runId = runId(label);
        assertTrue(dsl.select(MIGRATION_RUN.PARTITION_WISE).from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.RUN_ID.eq(runId)).fetchOne(MIGRATION_RUN.PARTITION_WISE),
                "the run is recorded as partition-wise");
        assertEquals(ChunksFixture.PARTITIONED_LEAVES, leafNames(runId), "the manifest lists every leaf");
        assertEquals(0, dsl.fetchCount(MIGRATION_RUN_LEAF, MIGRATION_RUN_LEAF.RUN_ID.eq(runId)
                        .and(MIGRATION_RUN_LEAF.BOUNDARY_COUNT.eq(0L))),
                "every leaf holds rows, so none is empty");
    }

    /**
     * A partition-wise ordered-key run over the two-level tree, with the
     * four-column index declared on the parent and inherited by every leaf,
     * processes every row exactly once.
     */
    @Test
    void processesATwoLevelPartitionedTableOrderedKeyPartitionWise() {
        String label = uniqueLabel("four-partition-wise-ordered");

        Routines.runMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PARTITIONED, label, 9, 2,
                "t", ChunkingStrategy.ordered_key, 1, 0, PARTITIONED_ORDERED_INDEX, true, 3, 1000);

        assertEveryRowDoneOnce(TEST_CHUNKS_PARTITIONED, "partition-wise ordered key");
        assertTrue(runCompleted(label), "the partition-wise run completes");
        assertArrayEquals(ORDERED_KINDS, dsl.select(MIGRATION_RUN.ORDERED_KEY_KINDS).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label)).fetchOne(MIGRATION_RUN.ORDERED_KEY_KINDS));
    }

    /**
     * A failed primary-key run over the plain table resumes under the same label:
     * the chunks completed before the failure are not processed again and the
     * rest are processed once.
     */
    @Test
    void resumesAFourColumnRunExactlyOnce() {
        String label = uniqueLabel("four-resume");

        assertSqlState("22012", () -> Routines.runMigrationChunks(dsl.configuration(), FAIL_ON_ONE_ROW,
                SCHEMA, PLAIN, label, 20, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000));
        long runId = runId(label);
        assertTrue(completedBoundaries(runId) >= 1, "a chunk completed before the failure");
        assertTrue(completedBoundaries(runId) < totalChunks(runId), "the run stopped part way");
        assertFalse(runCompleted(runId), "the run is not complete");
        int doneBeforeResume = payloadCount(TEST_CHUNKS_FOUR, "x");
        assertTrue(doneBeforeResume > 0 && doneBeforeResume < ROWS, "the run processed part of the rows");

        Routines.setMigrationRunSqlText(dsl.configuration(), label, APPEND);
        Routines.runMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PLAIN, label, 20, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEveryRowDoneOnce(TEST_CHUNKS_FOUR, "after the resume");
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    /**
     * A partition-wise run that fails in a leaf resumes under the same label: the
     * leaves and chunks done before the failure are kept, and every row is
     * processed exactly once.
     */
    @Test
    void resumesATwoLevelPartitionWiseRunExactlyOnce() {
        String label = uniqueLabel("four-partition-wise-resume");

        assertSqlState("22012", () -> Routines.runMigrationChunks(dsl.configuration(), FAIL_ON_ONE_ROW,
                SCHEMA, PARTITIONED, label, 9, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, true, 3, 1000));
        long runId = runId(label);
        assertTrue(completedBoundaries(runId) >= 1, "a chunk completed before the failure");
        assertFalse(runCompleted(runId), "the run is not complete");
        int doneBeforeResume = payloadCount(TEST_CHUNKS_PARTITIONED, "x");
        assertTrue(doneBeforeResume > 0 && doneBeforeResume < ROWS, "the run processed part of the rows");

        Routines.setMigrationRunSqlText(dsl.configuration(), label, APPEND);
        Routines.runMigrationChunks(dsl.configuration(), APPEND, SCHEMA, PARTITIONED, label, 9, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, true, 3, 1000);

        assertEveryRowDoneOnce(TEST_CHUNKS_PARTITIONED, "after the resume");
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    private long populate(String table, String labelPrefix, int chunkSize, ChunkingStrategy strategy,
                          String index) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, table, uniqueLabel(labelPrefix), APPEND, chunkSize, 1,
                strategy, "t", 0, index, false, 3, 1000);
    }

    private List<String> leafNames(long runId) {
        return dsl.select(MIGRATION_RUN_LEAF.LEAF_NAME).from(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .orderBy(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .fetch(MIGRATION_RUN_LEAF.LEAF_NAME);
    }

    private void assertEveryRowDoneOnce(org.jooq.Table<?> table, String context) {
        assertEquals(ROWS, dsl.fetchCount(table), context + ": row count");
        assertEquals(ROWS, payloadCount(table, "x"),
                context + ": every row processed exactly once (a repeat would hold xx)");
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }
}
