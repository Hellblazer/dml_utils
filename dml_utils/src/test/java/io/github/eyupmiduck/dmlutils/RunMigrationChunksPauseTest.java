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
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a pause reaches a run whose coordinator is already processing
 * chunks: it stops launching, waits for the in-flight workers to commit, leaves
 * the run resumable and does not auto-resume on a repeated call.
 */
class RunMigrationChunksPauseTest extends PostgresTestBase {

    private static final String FAST_TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * The chunk takes a transaction-level advisory lock before it sleeps. The test
     * polls {@code pg_locks} for that lock, so it knows a worker has actually
     * claimed a boundary before pausing; a fixed sleep could pause the run before
     * any worker launched on a slow CI agent.
     */
    private static String slowTemplate(int lockKey) {
        return "SELECT pg_advisory_xact_lock(" + lockKey + ", 4242), pg_sleep(1)"
                + " FROM <driving_table> WHERE <chunking_clause>";
    }

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
     * A pause from another session stops launching new chunks, lets the in-flight
     * chunk commit, leaves the remaining boundaries pending and is not
     * auto-resumed by a repeated run call.
     */
    @Test
    void pausesAnInFlightRunAndLeavesItResumable() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("pause-in-flight");

        int lockKey = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(), slowTemplate(lockKey),
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t",
                        ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitChunkInFlight(label, lockKey);
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));

        run.get(30, TimeUnit.SECONDS);

        MigrationRunRecord record = migrationRun(runId);
        assertNotNull(record.getPausedAt(), "the run is paused");
        assertNull(record.getCompletedAt(), "the paused run did not complete");
        assertNull(record.getCancelledAt(), "pause does not cancel the run");
        assertTrue(completedBoundaries(runId) > 0,
                "the in-flight chunk commits before the coordinator returns");
        assertTrue(completedBoundaries(runId) < migrationRun(runId).getTotalChunks(),
                "pause leaves later chunks pending");
        assertEquals(0, cancelledBoundaries(runId),
                "pause does not mark boundaries cancelled");

        long completedBefore = completedBoundaries(runId);
        Routines.runMigrationChunks(dsl.configuration(), FAST_TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEquals(completedBefore, completedBoundaries(runId),
                "a repeated call must not auto-resume a paused run");

        Routines.setMigrationRunSqlText(dsl.configuration(), label, FAST_TEMPLATE);
        assertEquals(runId, Routines.resumeMigrationRun(dsl.configuration(), label));
        Routines.runMigrationChunks(dsl.configuration(), FAST_TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        record = migrationRun(runId);
        assertNotNull(record.getCompletedAt(), "the resumed run completes");
        assertNull(record.getPausedAt(), "completion clears the pause");
        assertEquals(record.getTotalChunks().intValue(), completedBoundaries(runId),
                "every chunk is processed");
        assertEquals(0, cancelledBoundaries(runId), "no chunk is cancelled");
    }

    /**
     * A long per-chunk sleep is interrupted by a pause instead of delaying the
     * pause for the full sleep.
     */
    @Test
    void pauseInterruptsAThrottleSleep() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6);
        String label = uniqueLabel("pause-sleep");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(), FAST_TEMPLATE,
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                        ChunkingStrategy.primary_key, 1, 60_000, null, false, 3, 1000);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitThrottleSleep(label);
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));

        // The throttle is 60s, so a coordinator that does not abort it on pause
        // cannot finish inside this deadline: the test fails if the sleep is not
        // interrupted, not merely later.
        run.get(10, TimeUnit.SECONDS);

        MigrationRunRecord record = migrationRun(runId);
        assertNotNull(record.getPausedAt(), "the sleeping run is paused");
        assertNull(record.getCompletedAt(), "the paused run did not complete");
    }

    /**
     * If every chunk is already complete but the run has not recorded completion,
     * a pause at the top of the call lets completion win instead of leaving the
     * run paused forever.
     */
    @Test
    void aPausedRunWithEveryChunkCompleteCompletesInsteadOfStayingPaused() {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-complete-race");
        Long runId = populateSingleRelation(label, FAST_TEMPLATE, 2);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED, MIGRATION_RUN.TOTAL_CHUNKS)
                .set(MIGRATION_RUN.PAUSED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        Routines.runMigrationChunks(dsl.configuration(), FAST_TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        MigrationRunRecord record = migrationRun(runId);
        assertNotNull(record.getCompletedAt(), "completion wins over pause");
        assertNull(record.getPausedAt(), "completion clears the pause");
    }

    /**
     * A pause that lands while the final chunk is in flight lets that chunk
     * commit and then completes the run instead of leaving it paused.
     */
    @Test
    void completionWinsWhenPauseLandsWhileTheLastChunkIsInFlight() throws Exception {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-last-chunk");

        int lockKey = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(), slowTemplate(lockKey),
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                        ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitChunkInFlightAfter(label, 1, lockKey);
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));

        run.get(30, TimeUnit.SECONDS);

        MigrationRunRecord record = migrationRun(runId);
        assertNotNull(record.getCompletedAt(), "completion wins over pause");
        assertNull(record.getPausedAt(), "completion clears the pause");
        assertEquals(0, cancelledBoundaries(runId), "no chunk is cancelled");
    }

    /**
     * Counts cancelled chunk boundaries.
     *
     * @param runId the run id
     * @return the cancelled boundary count
     */
    private int cancelledBoundaries(long runId) {
        return dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull()));
    }


    /**
     * Waits until the first chunk has completed and the coordinator is inside the
     * throttle's {@code pg_sleep}, so the pause genuinely interrupts the sleep
     * rather than being seen at the next launch gate. A completed chunk alone does
     * not prove that: the coordinator may not have reached the sleep yet.
     *
     * @param label the run label
     * @return the run id
     * @throws InterruptedException if the wait is interrupted
     */
    private long awaitThrottleSleep(String label) throws Exception {
        // Probe as the owner: the coordinator runs on an owner connection, and
        // another role cannot read its query text from pg_stat_activity.
        try (Connection probeConnection = openOwnerConnection()) {
            DSLContext probe = DSL.using(probeConnection, SQLDialect.POSTGRES);
            for (int attempt = 0; attempt < 400; attempt++) {
                Record row = probe.fetchOne(
                        "SELECT run_id FROM dml_utils_data.migration_run"
                                + " WHERE label = ? AND total_chunks_completed > 0"
                                + " AND completed_at IS NULL",
                        label);
                if (row != null
                        && Boolean.TRUE.equals(probe.fetchOne(
                        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity AS a"
                                + " WHERE a.datname = pg_catalog.current_database()"
                                + " AND a.query LIKE '%run_migration_chunks%'"
                                + " AND a.wait_event = 'PgSleep')").get(0, Boolean.class))) {
                    return row.get(0, Long.class);
                }
                Thread.sleep(25);
            }
        }
        return fail("the coordinator never entered the throttle sleep for " + label);
    }


    /**
     * Waits until the background call has created the run and a worker is running
     * a chunk, proved by the advisory lock the slow template takes.
     *
     * @param label the run label
     * @return the run id
     * @throws InterruptedException if the wait is interrupted
     */
    private long awaitChunkInFlight(String label, int lockKey) throws InterruptedException {
        return awaitChunkInFlightAfter(label, 0, lockKey);
    }

    /**
     * Waits until at least {@code completedAtLeast} chunks have completed and a
     * worker is running a chunk, proved by the advisory lock the slow template
     * takes.
     *
     * @param label            the run label
     * @param completedAtLeast the minimum completed chunk count
     * @return the run id
     * @throws InterruptedException if the wait is interrupted
     */
    private long awaitChunkInFlightAfter(String label, int completedAtLeast, int lockKey)
            throws InterruptedException {
        // Probe on an owner connection: pg_background workers launched by the
        // owner are only fully visible in pg_stat_activity to their own role, and
        // this ties the observation to this run's chunk worker instead of any
        // other session holding the same advisory key.
        try (Connection probeConnection = openOwnerConnection()) {
            DSLContext probe = DSL.using(probeConnection, SQLDialect.POSTGRES);
            for (int attempt = 0; attempt < 200; attempt++) {
                Record row = probe.fetchOne(
                        "SELECT r.run_id FROM dml_utils_data.migration_run AS r"
                                + " WHERE r.label = ?"
                                + " AND r.total_chunks_completed >= ?"
                                + " AND EXISTS (SELECT 1 FROM dml_utils_data.migration_boundary AS b"
                                + " WHERE b.run_id = r.run_id)",
                        label, completedAtLeast);
                if (row != null) {
                    long runId = row.get(0, Long.class);
                    if (Boolean.TRUE.equals(probe.fetchOne(
                                    "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks AS l"
                                            + " JOIN pg_catalog.pg_stat_activity AS a ON a.pid = l.pid"
                                            + " WHERE l.locktype = 'advisory'"
                                            + " AND l.classid = ? AND l.objid = 4242"
                                            + " AND a.backend_type = 'pg_background'"
                                            + " AND a.datname = pg_catalog.current_database()"
                                            + " AND a.query LIKE ?)",
                                    lockKey,
                                    "%process_migration_chunk(" + runId + ",%")
                            .get(0, Boolean.class))) {
                        return runId;
                    }
                }
                Thread.sleep(50);
            }
            String observed = probe.fetchOne("SELECT coalesce(string_agg(r.label || ':'"
                            + " || r.total_chunks_completed || ':' || r.run_id, ','), 'no runs')"
                            + " FROM dml_utils_data.migration_run AS r WHERE r.label = ?", label)
                    .get(0, String.class);
            return fail("the chunk worker for " + label
                    + " did not take the advisory lock; observed " + observed);
        } catch (SQLException error) {
            throw new CompletionException(error);
        }
    }
}
