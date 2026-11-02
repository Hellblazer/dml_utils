package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOther.TEST_OTHER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code run_migration_chunks} with the block (ctid) strategy.
 *
 * <p>Block chunking is only valid for a quiescent, read-only driving table: a
 * non-HOT UPDATE changes a tuple's ctid and may move it to another block, and a
 * heap rewrite (for example VACUUM FULL or TRUNCATE) changes every ctid. These
 * tests therefore use a workload that inserts each source row into a separate
 * target, leaving the source untouched.
 */
class RunMigrationChunksBlockTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "INSERT INTO dml_utils_fixtures.test_other (id, payload) "
                    + "SELECT t.id, t.payload FROM <driving_table> WHERE <chunking_clause>";

    private static final String SCHEMA = "dml_utils_fixtures";

    /**
     * Clears the source, target and run metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_OTHER).execute();
    }

    /**
     * A block-chunked run copies every source row into the target exactly once
     * (a duplicate would violate the target's primary key) and records the
     * strategy and filepath.
     */
    @Test
    void processesEveryRowExactlyOnce() {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("block");

        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                label, 1, 1, "t", ChunkingStrategy.blocks, 1, 0, null);

        assertEquals(10, dsl.fetchCount(TEST_OTHER), "every source row is inserted exactly once");
        assertEquals(10, dsl.fetchCount(TEST_BIGINT), "the source table is unchanged");
        assertTrue(runCompleted(label), "the run should be complete");
        assertEquals(ChunkingStrategy.blocks, chunkBy(label), "the run records the block strategy");
        assertNotNull(filepath(label), "a block run records the driving table filepath");
    }

    /**
     * A resumed run uses the strategy recorded on the run, not the one passed to
     * the call: a block run resumed with {@code primary_key} still chunks by
     * blocks.
     */
    @Test
    void resumesWithTheStoredStrategy() {
        seedBigint(1, 2, 3, 4, 5);
        String label = uniqueLabel("block-resume");

        // Create the block run's boundaries without processing it.
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TEST_BIGINT.getName(), label, TEMPLATE, 1, 1,
                ChunkingStrategy.blocks, "t", 0, null);

        // Resume with a different requested strategy; the stored one wins.
        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, SCHEMA, TEST_BIGINT.getName(),
                label, 1, 1, "t", ChunkingStrategy.primary_key, 1, 0, null);

        assertEquals(5, dsl.fetchCount(TEST_OTHER), "the stored block strategy processed every row");
        assertEquals(ChunkingStrategy.blocks, chunkBy(label), "the stored strategy is unchanged");
    }

    /**
     * A heap rewrite before a resume fails closed: the recorded filepath no
     * longer matches, so the stale block boundaries are not reused.
     */
    @Test
    void rejectsAResumeAfterARewrite() {
        seedBigint(1, 2, 3, 4, 5);
        String label = uniqueLabel("block-rewrite");

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), SCHEMA, TEST_BIGINT.getName(), label, TEMPLATE, 1, 1,
                ChunkingStrategy.blocks, "t", 0, null);

        // TRUNCATE gives the table a new relfilenode, changing its filepath.
        dsl.execute("TRUNCATE dml_utils_fixtures.test_bigint");

        assertSqlState("22023", () -> Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                SCHEMA, TEST_BIGINT.getName(), label, 1, 1, "t", ChunkingStrategy.blocks, 1, 0, null));
    }

    /**
     * The rendered block chunk SQL plans with a Tid Range Scan. For the INSERT
     * workload the scan is nested under the ModifyTable root, so the plan text is
     * searched rather than requiring a particular root node.
     */
    @Test
    void rendersACtidRangeThatPlansAsATidRangeScan() {
        seedBigint(1, 2, 3, 4, 5);

        String sql = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.renderBlockChunkSql(
                dsl.configuration(), TEMPLATE, SCHEMA, TEST_BIGINT.getName(), "t", 0L, 1L);
        JSON plan = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.explainQueryPlan(
                dsl.configuration(), sql);

        assertTrue(plan.data().contains("Tid Range Scan"),
                "the ctid range should plan as a Tid Range Scan: " + plan.data());
    }

    /**
     * An empty table produces a run with no boundaries that still completes.
     */
    @Test
    void anEmptyTableCompletesWithNoChunks() {
        String label = uniqueLabel("block-empty");

        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, SCHEMA, TEST_BIGINT.getName(),
                label, 1, 1, "t", ChunkingStrategy.blocks, 1, 0, null);

        assertTrue(runCompleted(label), "an empty run should complete");
        long runId = dsl.select(MIGRATION_RUN.RUN_ID)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.RUN_ID);
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId)),
                "an empty table has no boundaries");
    }

    /**
     * A multi-threaded block run still processes every row exactly once.
     */
    @Test
    void processesEveryRowExactlyOnceAcrossThreads() {
        dsl.execute("INSERT INTO dml_utils_fixtures.test_bigint (id, payload)"
                + " SELECT g, repeat('x', 200) FROM generate_series(1, 1000) g");
        String label = uniqueLabel("block-threads");

        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, SCHEMA, TEST_BIGINT.getName(),
                label, 1, 4, "t", ChunkingStrategy.blocks, 1, 0, null);

        assertEquals(1000, dsl.fetchCount(TEST_OTHER), "every source row is copied exactly once");
        assertEquals(1000, dsl.fetchCount(TEST_BIGINT), "the source table is unchanged");
        assertTrue(runCompleted(label), "the run should be complete");
    }

    private ChunkingStrategy chunkBy(String label) {
        return dsl.select(MIGRATION_RUN.CHUNK_BY)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.CHUNK_BY);
    }

    private String filepath(String label) {
        return dsl.select(MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH);
    }
}
