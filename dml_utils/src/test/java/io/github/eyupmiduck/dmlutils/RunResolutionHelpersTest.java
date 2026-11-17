package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ResumeMigrationRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the run-resolution helpers: active-run lookup, autonomous creation,
 * and resume validation of an ordered-key descriptor and blocks filepath.
 */
class RunResolutionHelpersTest extends PostgresTestBase {

    private static final String TEMPLATE = "SELECT 1";
    private static final String ORDERED_INDEX = "test_ordered_key_created_at_id_idx";

    /**
     * Clears fixtures and metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * The active-run lookup returns the run, ignores a missing label and ignores
     * an archived run.
     */
    @Test
    void findsOnlyTheActiveRun() {
        String label = uniqueLabel("find-active");
        long runId = populate(label, ChunkingStrategy.primary_key);

        assertEquals(runId, Routines.findActiveMigrationRun(dsl.configuration(), label)
                .getFirst().getRunId());
        assertTrue(Routines.findActiveMigrationRun(dsl.configuration(),
                "no-such-label").isEmpty());

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ARCHIVED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertTrue(Routines.findActiveMigrationRun(dsl.configuration(), label).isEmpty());
    }

    /**
     * Creating a single-relation run populates its boundaries; a partition-wise
     * run creates only its manifest.
     */
    @Test
    void createsSingleRelationAndPartitionWiseRuns() {
        seedBigint(1, 2, 3, 4, 5);
        long single = Routines.createMigrationRun(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), uniqueLabel("create-single"),
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

        assertEquals(4, dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(single)));
        assertNotNull(run(single).getBoundariesCalculatedAt());
        assertEquals(3, run(single).getMaxRetries(), "the single run stores the retry count");
        assertEquals(1000, run(single).getRetryBackoffMs(),
                "the single run stores the retry backoff");

        long partitioned = Routines.createMigrationRun(dsl.configuration(),
                "dml_utils_fixtures", "test_partitioned_range",
                uniqueLabel("create-partitioned"), TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);

        assertTrue(run(partitioned).getPartitionWise());
        assertEquals(3, dsl.fetchCount(MIGRATION_RUN_LEAF,
                MIGRATION_RUN_LEAF.RUN_ID.eq(partitioned)));
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(partitioned)));
        assertEquals(3, run(partitioned).getMaxRetries(),
                "the partition-wise run stores the retry count");
        assertEquals(1000, run(partitioned).getRetryBackoffMs(),
                "the partition-wise run stores the retry backoff");
    }

    /**
     * A new ordered-key run without an index is rejected before creation.
     */
    @Test
    void createRejectsAnOrderedRunWithoutAnIndex() {
        assertSqlState("22023", () -> Routines.createMigrationRun(dsl.configuration(),
                "dml_utils_fixtures", "test_ordered_key", uniqueLabel("create-bad"),
                TEMPLATE, 2, 1, ChunkingStrategy.ordered_key, "t", 0, null, false, 3, 1000));
    }

    /**
     * Resuming a matching run returns its stored effective inputs.
     */
    @Test
    void resumesARequestedRun() {
        String label = uniqueLabel("resume-active");
        long runId = populate(label, ChunkingStrategy.ordered_key);
        MigrationRunRecord stored = run(runId);

        ResumeMigrationRun resumed = Routines.resumeMigrationRun(dsl.configuration(),
                stored, "dml_utils_fixtures", "test_ordered_key", TEMPLATE, 2, 1,
                "t", ChunkingStrategy.ordered_key, 0, ORDERED_INDEX, false, 3, 1000);

        assertEquals(TEMPLATE, resumed.getOEffectiveSqlText());
        assertEquals("dml_utils_fixtures", resumed.getOEffectiveSchemaName());
        assertEquals("test_ordered_key", resumed.getOEffectiveTableName());
        assertEquals("t", resumed.getOEffectiveAlias());
        assertEquals(1, resumed.getOEffectiveThreads());
        assertEquals(0, resumed.getOEffectiveSleepMs());
        assertEquals(ChunkingStrategy.ordered_key, resumed.getOChunkBy());
        assertFalse(resumed.getOPartitionWise());
        assertEquals(3, resumed.getOEffectiveMaxRetries());
        assertEquals(1000, resumed.getOEffectiveRetryBackoffMs());
    }

    /**
     * A resumed run uses its stored retry policy, not the call's inputs.
     */
    @Test
    void resumeUsesTheStoredRetryPolicy() {
        String label = uniqueLabel("resume-retries");
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), label, TEMPLATE, 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 7, 250);
        MigrationRunRecord stored = run(runId);

        ResumeMigrationRun resumed = Routines.resumeMigrationRun(dsl.configuration(),
                stored, schema(TEST_BIGINT), name(TEST_BIGINT), TEMPLATE, 1, 1,
                "t", ChunkingStrategy.primary_key, 0, null, false, 9, 500);

        assertEquals(7, resumed.getOEffectiveMaxRetries(), "the stored retries win");
        assertEquals(250, resumed.getOEffectiveRetryBackoffMs(), "the stored backoff wins");
    }

    /**
     * A stored ordered-key descriptor that no longer matches the live index fails
     * closed with {@code 55000}.
     */
    @Test
    void resumeRejectsAChangedOrderedDescriptor() {
        String label = uniqueLabel("resume-ordered");
        long runId = populate(label, ChunkingStrategy.ordered_key);
        MigrationRunRecord stored = run(runId);
        stored.setOrderedKeyColumns(new String[]{"id"});

        assertSqlState("55000", () -> Routines.resumeMigrationRun(dsl.configuration(),
                stored, "dml_utils_fixtures", "test_ordered_key", TEMPLATE, 1, 1,
                "t", ChunkingStrategy.ordered_key, 0, ORDERED_INDEX, false, 3, 1000));
    }

    /**
     * A stored blocks filepath that no longer matches the live heap fails closed
     * with {@code 22023} (assert_relation_filepath's invalid_parameter_value).
     */
    @Test
    void resumeRejectsAChangedBlocksFilepath() {
        String label = uniqueLabel("resume-blocks");
        long runId = populate(label, ChunkingStrategy.blocks);
        MigrationRunRecord stored = run(runId);
        stored.setDrivingTableRelationFilepath("base/1/999");

        assertSqlState("22023", () -> Routines.resumeMigrationRun(dsl.configuration(),
                stored, schema(TEST_BIGINT), name(TEST_BIGINT), TEMPLATE, 1, 1,
                "t", ChunkingStrategy.blocks, 0, null, false, 3, 1000));
    }

    /**
     * Creates a single-relation run over the fixture for the strategy.
     *
     * @param label   the run label
     * @param chunkBy the chunking strategy
     * @return the run id
     */
    private long populate(String label, ChunkingStrategy chunkBy) {
        String table = chunkBy == ChunkingStrategy.ordered_key
                ? "test_ordered_key" : name(TEST_BIGINT);
        String tableSchema = chunkBy == ChunkingStrategy.ordered_key
                ? "dml_utils_fixtures" : schema(TEST_BIGINT);
        String index = chunkBy == ChunkingStrategy.ordered_key ? ORDERED_INDEX : null;
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                tableSchema, table, label, TEMPLATE, 1, 1, chunkBy, "t", 0, index,
                false, 3, 1000);
    }

    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
