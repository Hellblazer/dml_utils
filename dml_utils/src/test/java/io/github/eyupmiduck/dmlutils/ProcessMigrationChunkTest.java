package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.process_migration_chunk}: it claims a boundary,
 * runs the supplied chunk SQL, and records completion, and raises
 * {@code P0C01} for a missing or already-completed boundary.
 */
class ProcessMigrationChunkTest extends PostgresTestBase {

    private static String qualified(org.jooq.Table<?> table) {
        return table.getSchema().getName() + "." + table.getName();
    }

    private static String sqlStateOf(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    @BeforeEach
    void resetFixtures() {
        // Clear the metadata each test creates, so runs and boundaries from an
        // earlier method cannot leak into this one (boundaries and errors cascade).
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * Running a chunk marks the rows in range and records the boundary as
     * completed.
     */
    @Test
    void runsChunkSqlAndClaimsTheBoundary() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        long runId = populate(4);

        processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'done' WHERE id >= 1 AND id < 5");

        assertTrue(boundaryStarted(runId, 0), "the first chunk should set started_at");
        assertTrue(boundaryCompleted(runId, 0), "the first chunk should set completed_at");
        assertEquals(4, payloadCount(TEST_BIGINT, "done"), "rows 1..4 should be updated");
        assertEquals(6, dsl.fetchCount(TEST_BIGINT, TEST_BIGINT.PAYLOAD.isNull()),
                "rows outside the chunk range must be untouched");
    }

    /**
     * A chunk whose SQL raises after a successful change rolls back the change
     * (and the claim too), so a failed chunk leaves no partial effect and a later
     * call can retry it.
     */
    @Test
    void rollsBackAFailedChunkAndAllowsARetry() {
        createSource(1, 2, 3, 4);
        long runId = populate(2);

        // Update the chunk rows, then fail: the update must not survive.
        String mutateThenFail = "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'leaked' WHERE id >= 1 AND id < 3;"
                + " SELECT 1 / 0";
        assertSqlState("22012", () -> processChunk(runId, 0, mutateThenFail));
        assertFalse(boundaryStarted(runId, 0),
                "a failed chunk must not leave the boundary started");
        assertFalse(boundaryCompleted(runId, 0),
                "a failed chunk must not leave the boundary claimed");
        assertEquals(0, dsl.fetchCount(TEST_BIGINT, TEST_BIGINT.PAYLOAD.isNotNull()),
                "the change from the failed chunk must be rolled back");

        processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'done' WHERE id >= 1 AND id < 3");
        assertTrue(boundaryCompleted(runId, 0), "the retry should claim the boundary");
        assertEquals(2, payloadCount(TEST_BIGINT, "done"), "the retry should update the chunk rows");
    }

    /**
     * An unknown boundary raises {@code P0C01}.
     */
    @Test
    void rejectsMissingBoundary() {
        createSource(1, 2, 3);
        long runId = populate(2);

        assertSqlState("P0C01", () -> processChunk(runId, 999, "SELECT 1"));
    }

    /**
     * An already-completed boundary raises {@code P0C01} and does not run the
     * SQL again.
     */
    @Test
    void rejectsAlreadyCompletedBoundary() {
        createSource(1, 2, 3, 4);
        long runId = populate(2);

        processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'done' WHERE id >= 1 AND id < 3");

        assertSqlState("P0C01", () -> processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'again' WHERE id >= 1 AND id < 3"));
        assertEquals(2, payloadCount(TEST_BIGINT, "done"), "the second call must not run the SQL");
    }

    /**
     * A cancelled boundary also raises {@code P0C01} and is not executed.
     */
    @Test
    void rejectsCancelledBoundary() {
        createSource(1, 2, 3, 4);
        long runId = populate(2);
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .execute();

        assertSqlState("P0C01", () -> processChunk(runId, 0, "SELECT 1"));
    }

    /**
     * Two concurrent claims for the same boundary: the atomic claim lets exactly
     * one caller run the chunk SQL, and the other receives {@code P0C01} without
     * running it.
     */
    @Test
    void claimsABoundaryOnlyOnceUnderConcurrency() throws Exception {
        createSource(1, 2, 3, 4);
        long runId = populate(2);

        // A non-idempotent effect (append, not assign) so the final state reveals
        // how many times the chunk SQL actually ran, not just that the boundary
        // was claimed once.
        String sql = "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = coalesce(payload, '') || 'x' WHERE id >= 1 AND id < 3";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                try (Connection connection = openTestConnection()) {
                    DSLContext context = DSL.using(connection, SQLDialect.POSTGRES);
                    start.await();
                    try {
                        Routines.processMigrationChunk(
                                context.configuration(), runId, 0L, sql);
                        return true;
                    } catch (DataAccessException e) {
                        if ("P0C01".equals(sqlStateOf(e))) {
                            return false;
                        }
                        throw e;
                    }
                }
            };
            Future<Boolean> first = pool.submit(claim);
            Future<Boolean> second = pool.submit(claim);
            start.countDown();

            boolean firstClaimed = first.get();
            boolean secondClaimed = second.get();

            assertNotEquals(firstClaimed, secondClaimed,
                    "exactly one caller should claim the boundary");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertTrue(boundaryCompleted(runId, 0), "the boundary should be completed once");
        assertEquals(2, dsl.fetchCount(TEST_BIGINT, TEST_BIGINT.PAYLOAD.eq("x")),
                "the losing caller must not have run the chunk SQL, so each row is"
                        + " updated exactly once (payload 'x', not 'xx')");
    }

    private void createSource(long... ids) {
        seedBigint(ids);
    }

    private long populate(int chunkSize) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                "process-chunk-test-" + UUID.randomUUID(), "SELECT 1", chunkSize, 1, ChunkingStrategy.primary_key, "t", 0, null, false);
    }

    private void processChunk(long runId, long boundaryNo, String sql) {
        Routines.processMigrationChunk(dsl.configuration(), runId, boundaryNo, sql);
    }

}
