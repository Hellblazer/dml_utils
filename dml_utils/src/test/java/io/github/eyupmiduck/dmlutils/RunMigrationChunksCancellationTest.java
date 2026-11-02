package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a cancellation reaches a run whose coordinator is already
 * processing chunks: the coordinator stops launching, cancels the in-flight
 * workers, records the cancellation and returns.
 */
class RunMigrationChunksCancellationTest extends PostgresTestBase {

    /**
     * The chunk takes a transaction-level advisory lock before it sleeps. The test
     * polls {@code pg_locks} for that lock, so it knows a worker has actually
     * claimed a boundary before cancelling; a fixed sleep could cancel the run
     * before any worker launched on a slow CI agent.
     */
    private static final String SLOW_TEMPLATE =
            "SELECT pg_advisory_xact_lock(1, 4242), pg_sleep(1)"
                    + " FROM <driving_table> WHERE <chunking_clause>";

    private static final String FAST_TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Clears the fixture table and the migration metadata so each test starts
     * clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A cancellation from another session stops the run, cancels the in-flight
     * chunks (so no further chunk commits) and leaves the run unfinished.
     */
    @Test
    void cancelsAnInFlightRunAndStopsItsWorkers() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("cancel-in-flight");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(), SLOW_TEMPLATE,
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 5, 2, "t",
                        ChunkingStrategy.primary_key, 1, 0, null);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitChunkInFlight(label);
        Long cancelled = Routines.cancelMigrationRun(dsl.configuration(), label);
        assertEquals(runId, cancelled, "the active run is cancelled");

        run.get(30, TimeUnit.SECONDS);

        MigrationRunRecord record = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(record.getCancelledAt(), "the run is cancelled");
        assertNull(record.getCompletedAt(), "the cancelled run did not complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())),
                "an in-flight chunk is cancelled, not completed");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the remaining boundaries are marked cancelled");
    }

    /**
     * The caller role can cancel an in-flight run even though it has only
     * SELECT on the metadata tables; the owner-owned cancellation helper writes
     * the marks.
     */
    @Test
    void callerCanCancelAnInFlightRun() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6);
        String label = uniqueLabel("cancel-caller");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection connection = openTestConnection()) {
                setRole(connection, "dml_utils_caller");
                DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(caller.configuration(), SLOW_TEMPLATE,
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t",
                        ChunkingStrategy.primary_key, 1, 0, null);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitChunkInFlight(label);
        try (Connection connection = openTestConnection()) {
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);
            assertEquals(runId, Routines.cancelMigrationRun(caller.configuration(), label));
        }

        run.get(30, TimeUnit.SECONDS);

        MigrationRunRecord record = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(record.getCancelledAt(), "the run is cancelled");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the remaining boundaries are marked cancelled");
    }

    /**
     * A long per-chunk sleep is interrupted by cancellation instead of delaying
     * the cancellation for the full sleep.
     */
    @Test
    void cancellationInterruptsAThrottleSleep() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6);
        String label = uniqueLabel("cancel-sleep");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(), FAST_TEMPLATE,
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                        ChunkingStrategy.primary_key, 1, 5_000, null);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitCompletedChunk(label);
        Long cancelled = Routines.cancelMigrationRun(dsl.configuration(), label);
        assertEquals(runId, cancelled, "the active run is cancelled");

        run.get(2, TimeUnit.SECONDS);

        MigrationRunRecord record = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(record.getCancelledAt(), "the sleeping run is cancelled");
        assertNull(record.getCompletedAt(), "the cancelled run did not complete");
    }

    /**
     * Waits until the run has completed at least one chunk and is therefore in
     * the throttle sleep before launching the next one.
     */
    private long awaitCompletedChunk(String label) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Record row = dsl.fetchOne(
                    "SELECT run_id FROM dml_utils_data.migration_run"
                            + " WHERE label = ? AND total_chunks_completed > 0",
                    label);
            if (row != null) {
                return row.get(0, Long.class);
            }
            Thread.sleep(50);
        }
        fail("the run did not complete its first chunk");
        return -1;
    }

    /**
     * Waits until the background call has created the run and a worker is running
     * a chunk, proved by the advisory lock the slow template takes.
     *
     * @param label the run label
     * @return the run id
     * @throws InterruptedException if the wait is interrupted
     */
    private long awaitChunkInFlight(String label) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Record row = dsl.fetchOne(
                    "SELECT r.run_id FROM dml_utils_data.migration_run AS r"
                            + " WHERE r.label = ?"
                            + " AND EXISTS (SELECT 1 FROM dml_utils_data.migration_boundary AS b"
                            + " WHERE b.run_id = r.run_id)",
                    label);
            if (row != null
                    && Boolean.TRUE.equals(dsl.fetchOne(
                    "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks"
                            + " WHERE locktype = 'advisory' AND classid = 1 AND objid = 4242"
                            + " AND pid <> pg_backend_pid())").get(0, Boolean.class))) {
                return row.get(0, Long.class);
            }
            Thread.sleep(50);
        }
        fail("no chunk worker took the advisory lock");
        return -1;
    }
}
