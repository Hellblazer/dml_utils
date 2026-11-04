package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationErrorRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.NextMigrationChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the chunk-runner helpers: partition preparation, next-boundary
 * selection with the per-leaf terminal, autonomous failure recording and the
 * cancellation-aware throttle.
 */
class ChunkRunnerHelpersTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Clears fixtures and metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * Preparing a partition-wise run calculates and finalizes its boundaries and
     * checks the tree; a cancelled run returns false.
     */
    @Test
    void preparesPartitionWiseExecution() {
        insertRangeRows(0L, 1L, 200L);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range", uniqueLabel("prepare"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true);

        assertTrue(Routines.preparePartitionWiseExecution(dsl.configuration(), runId,
                "prepare"));
        assertNotNull(run(runId).getBoundariesCalculatedAt());

        long cancelled = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range", uniqueLabel("prepare-cancel"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(cancelled))
                .execute();

        assertFalse(Routines.preparePartitionWiseExecution(dsl.configuration(), cancelled,
                "prepare-cancel"));
    }

    /**
     * The next-boundary helper returns the first unclaimed chunk, advances when a
     * boundary is in flight, and returns none when everything is in flight.
     */
    @Test
    void selectsTheNextChunk() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), uniqueLabel("next-single"),
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false);

        NextMigrationChunk first = next(runId, false, new Long[0]);
        assertEquals(0L, first.getOBoundaryNo());
        assertEquals(schema(TEST_BIGINT), first.getOChunkSchemaName());
        assertEquals(name(TEST_BIGINT), first.getOChunkTableName());
        assertEquals("1", first.getOStartValues()[0]);
        assertEquals("3", first.getOEndValues()[0]);
        assertFalse(first.getOIsFinal());

        NextMigrationChunk second = next(runId, false, new Long[]{0L});
        assertEquals(1L, second.getOBoundaryNo());
        assertEquals("3", second.getOStartValues()[0]);
        assertEquals("5", second.getOEndValues()[0]);

        assertNull(next(runId, false, new Long[]{0L, 1L, 2L, 3L}).getOBoundaryNo());
    }

    /**
     * For a partition-wise run, the last chunk of a leaf ends at the manifest
     * high-water snapshot and is marked final.
     */
    @Test
    void usesTheLeafTerminalSnapshot() {
        insertRangeRows(0L, 1L, 200L);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range", uniqueLabel("next-partition"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        NextMigrationChunk lowFirst = next(runId, true, new Long[0]);
        assertEquals(0L, lowFirst.getOBoundaryNo());
        assertEquals("test_partitioned_range_low", lowFirst.getOChunkTableName());
        assertFalse(lowFirst.getOIsFinal());

        NextMigrationChunk lowLast = next(runId, true, new Long[]{0L});
        assertEquals(1L, lowLast.getOBoundaryNo());
        assertEquals("test_partitioned_range_low", lowLast.getOChunkTableName());
        assertEquals("1", lowLast.getOEndValues()[0]);
        assertTrue(lowLast.getOIsFinal());
    }

    /**
     * A worker failure is recorded in its own autonomous transaction.
     */
    @Test
    void recordsAWorkerFailure() {
        seedBigint(1, 2);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), uniqueLabel("record-failure"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false);

        Routines.recordMigrationWorkerFailure(dsl.configuration(), runId, 0L,
                "55000", "boom");

        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(error);
        assertEquals(0L, error.getBoundaryNo());
        assertEquals("55000", error.getSqlstate());
        assertEquals("boom", error.getMessage());
    }

    /**
     * The throttle returns immediately for zero and reports a cancellation while
     * sleeping.
     */
    @Test
    void sleepsAndReportsCancellation() {
        seedBigint(1, 2);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), uniqueLabel("sleep"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false);

        assertNull(Routines.sleepBetweenMigrationChunks(dsl.configuration(), runId, 0));

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertNotNull(Routines.sleepBetweenMigrationChunks(dsl.configuration(), runId, 10));
    }

    private NextMigrationChunk next(long runId, boolean partitionWise, Long[] inFlight) {
        return Routines.nextMigrationChunk(dsl.configuration(), runId, partitionWise,
                new String[]{"bigint"}, inFlight, schema(TEST_BIGINT), name(TEST_BIGINT));
    }


    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
