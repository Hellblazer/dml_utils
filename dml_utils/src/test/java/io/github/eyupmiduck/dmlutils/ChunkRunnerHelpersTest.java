package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationErrorRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.NextMigrationChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
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
        resetRunsBigintAndRange();
    }

    /**
     * A NULL run id is rejected by the non-null bigint domain instead of silently
     * matching no row.
     */
    @Test
    void rejectsANullRunIdWhenPreparing() {
        assertDomainViolation(() -> Routines.preparePartitionWiseExecution(
                dsl.configuration(), null, "prepare-null"));
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
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);

        assertTrue(Routines.preparePartitionWiseExecution(dsl.configuration(), runId,
                "prepare"));
        assertNotNull(migrationRun(runId).getBoundariesCalculatedAt());

        long cancelled = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range", uniqueLabel("prepare-cancel"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(cancelled))
                .execute();

        assertFalse(Routines.preparePartitionWiseExecution(dsl.configuration(), cancelled,
                "prepare-cancel"));

        long pausedBefore = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range", uniqueLabel("prepare-paused"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.PAUSED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(pausedBefore))
                .execute();
        assertFalse(Routines.preparePartitionWiseExecution(dsl.configuration(), pausedBefore,
                "prepare-paused"));
        assertNull(migrationRun(pausedBefore).getBoundariesCalculatedAt(),
                "a paused calculation does not finalize");

        long pausedAfter = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, "test_partitioned_range", uniqueLabel("prepare-paused-after"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);
        assertTrue(Routines.preparePartitionWiseExecution(dsl.configuration(), pausedAfter,
                "prepare-paused-after"));
        assertNotNull(migrationRun(pausedAfter).getBoundariesCalculatedAt());
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.PAUSED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(pausedAfter))
                .execute();
        assertFalse(Routines.preparePartitionWiseExecution(dsl.configuration(), pausedAfter,
                "prepare-paused-after"));
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
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

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
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);
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
     * A worker failure is recorded as a migration_error row with the given
     * boundary, SQLSTATE and message. The caller (the coordinator's worker) runs
     * in its own transaction; this routine itself runs in the caller's.
     */
    @Test
    void recordsAWorkerFailure() {
        seedBigint(1, 2);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), uniqueLabel("record-failure"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

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
     * sleeping. Pause interruption is covered by the runner pause test.
     */
    @Test
    void sleepsAndReportsCancellation() {
        seedBigint(1, 2);
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), uniqueLabel("sleep"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

        assertNull(Routines.sleepBetweenMigrationChunks(dsl.configuration(), runId, 0));

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertNotNull(Routines.sleepBetweenMigrationChunks(dsl.configuration(), runId, 10));
    }

    /**
     * A run row deleted through the supported path (archive, then delete the
     * archived terminal run) ends the sleep at the next slice instead of making
     * it loop on a vanished run.
     */
    @Test
    void sleepEndsWhenTheRunRowDisappears() {
        seedBigint(1, 2);
        String label = uniqueLabel("sleep-deleted");
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), label,
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines
                .archiveMigrationRun(dsl.configuration(), label);
        assertEquals(1, io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines
                        .deleteArchivedMigrationRuns(dsl.configuration(), label, 1000),
                "the archived terminal run is deleted");

        long startedAt = System.nanoTime();
        assertNull(Routines.sleepBetweenMigrationChunks(dsl.configuration(), runId, 10_000));

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(elapsedMillis < 2_000,
                () -> "the sleep should stop at the first slice, took " + elapsedMillis + "ms");
    }

    private NextMigrationChunk next(long runId, boolean partitionWise, Long[] inFlight) {
        String tableSchema = partitionWise ? PARTITION_SCHEMA : schema(TEST_BIGINT);
        String tableName = partitionWise ? PARTITIONED_RANGE : name(TEST_BIGINT);
        return Routines.nextMigrationChunk(dsl.configuration(), runId, partitionWise,
                new String[]{"bigint"}, inFlight, tableSchema, tableName);
    }


}
