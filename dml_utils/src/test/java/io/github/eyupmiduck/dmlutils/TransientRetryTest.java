package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the coordinator transient-retry policy: serialization failures,
 * deadlocks and lock-not-available errors are retried within the run's budget,
 * non-transient errors are not retried, exhaustion records the error, and pause
 * or cancellation interrupts the retry backoff.
 */
class TransientRetryTest extends PostgresTestBase {

    private static final String SEQUENCE = "public.retry_probe_seq";

    /**
     * Waits for the background coordinator. On timeout, cancels its backend and
     * the task so a stuck coordinator cannot keep using the fixture (the probe
     * sequence and functions) while the next test recreates them.
     *
     * @param run the background coordinator
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitCoordinator(CompletableFuture<Void> run) throws InterruptedException {
        try {
            run.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            run.cancel(true);
            // Tests run one at a time, so a live run_migration_chunks backend is
            // this test's coordinator; cancel it best-effort and wait for the
            // connection to close.
            for (Record row : dsl.fetch(
                    "SELECT pid FROM pg_catalog.pg_stat_activity"
                            + " WHERE query LIKE '%run_migration_chunks%'"
                            + " AND pid <> pg_catalog.pg_backend_pid()")) {
                Integer pid = row.get(0, Integer.class);
                try {
                    dsl.execute("SELECT pg_catalog.pg_cancel_backend(?)", pid);
                } catch (RuntimeException ignored) {
                    // The backend may already be gone.
                }
            }
            try {
                run.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best effort: the backend was asked to cancel; the failure below
                // still reports the timeout.
            }
            fail("the coordinator did not return: " + timeout.getMessage(), timeout);
        } catch (ExecutionException failure) {
            fail("the coordinator failed: " + failure.getCause(), failure);
        }
    }

    /**
     * The non-retry cases: a permanent error with a budget and a transient error
     * without one.
     *
     * @return the case parameters
     */
    private static java.util.stream.Stream<Arguments> nonRetryCases() {
        return java.util.stream.Stream.of(
                Arguments.of("retry-permanent", "fail_counted", "22012", 3),
                Arguments.of("retry-zero", "retry_once", "40P01", 0));
    }

    /**
     * Clears the fixture and metadata, then creates a fresh probe sequence and the
     * two transient-error functions.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.execute("DROP FUNCTION IF EXISTS public.retry_once(text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.fail_counted(text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.retry_mixed(bigint)");
        dsl.execute("DROP SEQUENCE IF EXISTS " + SEQUENCE);
        dsl.execute("CREATE SEQUENCE " + SEQUENCE);
        dsl.execute("GRANT USAGE, SELECT ON SEQUENCE " + SEQUENCE
                + " TO dml_utils_owner, dml_utils_caller");
        dsl.execute("CREATE FUNCTION public.retry_once(p_code text) RETURNS void"
                + " LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   IF nextval('" + SEQUENCE + "') = 1 THEN"
                + "     RAISE EXCEPTION 'transient' USING ERRCODE = p_code;"
                + "   END IF;"
                + " END $$");
        dsl.execute("CREATE FUNCTION public.fail_counted(p_code text) RETURNS void"
                + " LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   PERFORM nextval('" + SEQUENCE + "');"
                + "   RAISE EXCEPTION 'permanent' USING ERRCODE = p_code;"
                + " END $$");
        dsl.execute("CREATE FUNCTION public.retry_mixed(p_id bigint) RETURNS void"
                + " LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   IF p_id = 1 THEN"
                + "     IF nextval('" + SEQUENCE + "') = 1 THEN"
                + "       RAISE EXCEPTION 'transient' USING ERRCODE = '40P01';"
                + "     END IF;"
                + "   ELSE"
                + "     PERFORM pg_sleep(1);"
                + "   END IF;"
                + " END $$");
    }

    /**
     * Drops the probe functions and sequence.
     */
    @AfterEach
    void dropProbe() {
        dsl.execute("DROP FUNCTION IF EXISTS public.retry_once(text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.fail_counted(text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.retry_mixed(bigint)");
        dsl.execute("DROP SEQUENCE IF EXISTS " + SEQUENCE);
    }

    /**
     * Each transient SQLSTATE is retried once and then succeeds, leaving no
     * migration error row.
     */
    @Test
    void retriesEachTransientSqlstateOnceThenSucceeds() {
        seedBigint(1);
        for (String code : List.of("40001", "40P01", "55P03")) {
            dsl.deleteFrom(MIGRATION_RUN).execute();
            dsl.execute("ALTER SEQUENCE " + SEQUENCE + " RESTART WITH 1");
            String label = uniqueLabel("retry-" + code);

            migrationRun(label, template("retry_once", code), 2, 0);

            long runId = runId(label);
            assertTrue(runCompleted(label),
                    code + " should be retried and the run should complete");
            assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                    code + " should leave no error row after recovery");
            // max_retries counts retries after the initial attempt, and the probe
            // row is incremented per execution: one retry means two executions.
            assertEquals(2L, lastProbeValue(),
                    "the first attempt fails and the retry succeeds for " + code);
            assertEquals(1L, dsl.select(MIGRATION_RUN.TOTAL_ROWS_AFFECTED)
                            .from(MIGRATION_RUN)
                            .where(MIGRATION_RUN.RUN_ID.eq(runId))
                            .fetchOne(MIGRATION_RUN.TOTAL_ROWS_AFFECTED),
                    "a recovered retry counts the chunk rows once for " + code);
        }
    }

    /**
     * Retry exhaustion records the final transient error and leaves the run
     * incomplete.
     */
    @Test
    void retryExhaustionRecordsTheFinalError() {
        seedBigint(1);
        String label = uniqueLabel("retry-exhausted");

        assertSqlState("40P01", () -> migrationRun(label,
                template("fail_counted", "40P01"), 1, 0));

        long runId = runId(label);
        assertNull(migrationRun(runId).getCompletedAt(), "the exhausted run is not complete");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "exhaustion records exactly one error row");
        assertEquals("40P01", dsl.select(MIGRATION_ERROR.SQLSTATE)
                .from(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_ERROR.SQLSTATE));
        assertEquals(2L, lastProbeValue(),
                "one initial attempt plus one configured retry");
    }

    /**
     * A non-transient error is never retried even with a retry budget, and a zero
     * retry budget disables retries for a transient error: either way the error
     * is recorded once, the function is attempted once and the run stays
     * incomplete.
     *
     * @param labelPrefix the run label prefix
     * @param function    the probe function that fails
     * @param sqlstate    the expected SQLSTATE
     * @param maxRetries  the run's retry budget
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonRetryCases")
    void recordsTheFailureOnceWithoutRetrying(String labelPrefix, String function,
                                              String sqlstate, int maxRetries) {
        seedBigint(1);
        String label = uniqueLabel(labelPrefix);

        assertSqlState(sqlstate, () -> migrationRun(label,
                template(function, sqlstate), maxRetries, 0));

        long runId = runId(label);
        assertNull(migrationRun(runId).getCompletedAt(), "the failed run is not complete");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "the error is recorded once");
        assertEquals(1L, lastProbeValue(), "the function is attempted only once");
    }

    /**
     * A pause during the retry backoff leaves the failed boundary pending and the
     * run resumable; a resume with a zero backoff completes it.
     */
    @Test
    void pauseInterruptsTheRetryBackoffAndLeavesTheRunResumable() throws Exception {
        seedBigint(1);
        String label = uniqueLabel("retry-pause");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(),
                        template("retry_once", "40P01"), schema(TEST_BIGINT), name(TEST_BIGINT),
                        label, 1, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false,
                        3, 5_000);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitFirstAttempt(label);
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));
        awaitCoordinator(run);

        assertNotNull(migrationRun(runId).getPausedAt(), "the run is paused");
        assertNull(migrationRun(runId).getCompletedAt(), "the paused run is not complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "a pause during backoff does not record an error");
        assertEquals(1, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNull())
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the failed chunk boundary is still pending");

        Routines.setMigrationRunRetries(dsl.configuration(), label, 3, 0);
        assertEquals(runId, Routines.resumeMigrationRun(dsl.configuration(), label));
        migrationRun(label, template("retry_once", "40P01"), 3, 0);

        assertNotNull(migrationRun(runId).getCompletedAt(), "the resumed run completes");
        assertEquals(2L, lastProbeValue(), "the retry succeeds after the resume");
    }

    /**
     * A pause during the retry backoff must drain the other in-flight workers
     * before the coordinator returns.
     */
    @Test
    void pauseDuringBackoffDrainsOtherInFlightWorkers() throws Exception {
        seedBigint(1, 2);
        String label = uniqueLabel("retry-pause-drain");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(),
                        idTemplate("retry_mixed"), schema(TEST_BIGINT), name(TEST_BIGINT),
                        label, 1, 2, "t", ChunkingStrategy.primary_key, 1, 0, null, false,
                        3, 5_000);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitFirstAttempt(label);
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));
        awaitCoordinator(run);

        MigrationRunRecord record = migrationRun(runId);
        assertNotNull(record.getPausedAt(), "the run is paused");
        assertNull(record.getCompletedAt(), "the paused run is not complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "the transient failure is not recorded while paused");
        // Fixture assumption: chunk size 1 over ids 1..2 makes boundary 0 the
        // failing id 1 and boundary 1 the slow id 2, which the second worker
        // commits before the coordinator returns.
        assertEquals(1, completedBoundaries(runId),
                "the other in-flight chunk commits before the coordinator returns");
        assertEquals(1, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNull())
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the failed chunk remains pending");
    }

    /**
     * A cancellation during the retry backoff is terminal and records no chunk
     * error for the interrupted attempt.
     */
    @Test
    void cancellationInterruptsTheRetryBackoff() throws Exception {
        seedBigint(1);
        String label = uniqueLabel("retry-cancel");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(),
                        template("retry_once", "40P01"), schema(TEST_BIGINT), name(TEST_BIGINT),
                        label, 1, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false,
                        3, 5_000);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitFirstAttempt(label);
        assertEquals(runId, Routines.cancelMigrationRun(dsl.configuration(), label));
        awaitCoordinator(run);

        assertNotNull(migrationRun(runId).getCancelledAt(), "the run is cancelled");
        assertNull(migrationRun(runId).getCompletedAt(), "the cancelled run is not complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "cancellation during backoff records no chunk error");
        assertTrue(dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull())) > 0,
                "the remaining boundaries are marked cancelled");
    }

    /**
     * Runs the migration with the given retry policy.
     *
     * @param label        the run label
     * @param sqlText      the chunk template
     * @param maxRetries   the retry budget
     * @param retryBackoff the retry backoff in milliseconds
     */
    private void migrationRun(String label, String sqlText, int maxRetries, int retryBackoff) {
        Routines.runMigrationChunks(dsl.configuration(), sqlText,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false, maxRetries, retryBackoff);
    }

    /**
     * Builds the chunk template that calls the probe function.
     *
     * @param functionName the probe function
     * @param code         the SQLSTATE to raise
     * @return the chunk template
     */
    private String template(String functionName, String code) {
        return "SELECT public." + functionName + "('" + code + "')"
                + " FROM <driving_table> WHERE <chunking_clause>";
    }

    /**
     * Builds the chunk template that calls the probe function with the row id.
     *
     * @param functionName the probe function
     * @return the chunk template
     */
    private String idTemplate(String functionName) {
        return "SELECT public." + functionName + "(t.id)"
                + " FROM <driving_table> WHERE <chunking_clause>";
    }

    /**
     * Returns the probe sequence's last value.
     *
     * @return the last value
     */
    private long lastProbeValue() {
        return dsl.fetchOne("SELECT last_value FROM " + SEQUENCE).get(0, Long.class);
    }

    /**
     * Whether the probe sequence has been called at least once.
     *
     * @return true when {@code nextval} has run
     */
    private boolean probeCalled() {
        return Boolean.TRUE.equals(dsl.fetchOne("SELECT is_called FROM " + SEQUENCE)
                .get(0, Boolean.class));
    }

    /**
     * Waits until the first transient attempt has happened and the run row exists.
     *
     * @param label the run label
     * @return the run id
     * @throws InterruptedException if the wait is interrupted
     */
    private long awaitFirstAttempt(String label) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Record row = dsl.fetchOne(
                    "SELECT run_id FROM dml_utils_data.migration_run WHERE label = ?", label);
            if (row != null && probeCalled()) {
                return row.get(0, Long.class);
            }
            Thread.sleep(50);
        }
        return fail("the first retry attempt did not happen");
    }

}
