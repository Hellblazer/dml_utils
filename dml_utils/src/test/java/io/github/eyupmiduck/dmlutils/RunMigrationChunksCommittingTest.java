package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.run_migration_chunks_committing}: it does what
 * {@code run_migration_chunks} does (every row exactly once, the same run
 * metadata, resume, partition-wise, error recording, cancellation, pause and
 * resume, transient retries, READ COMMITTED) and differs
 * only in that it COMMITs between chunk launches, which the bloat tests in
 * {@link RunMigrationChunksBloatTest} measure.
 */
class RunMigrationChunksCommittingTest extends CommittingRunTestBase {

    /**
     * Appends one marker per processing, so a row processed twice ends with two.
     */
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x' WHERE <chunking_clause>";
    private static final String FAILING_TEMPLATE =
            "SELECT 1 / 0 FROM <driving_table> WHERE <chunking_clause>";
    private static final String ORDERED_KEY_INDEX = "test_ordered_key_created_at_id_idx";
    private static final String PROBE_SEQUENCE = "public.committing_probe_seq";

    /**
     * Clears the fixtures and the run metadata so each test starts clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_ORDERED_KEY).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A primary-key run processes every row exactly once and leaves the run
     * metadata the function leaves for the same table: the same chunk counts and
     * the same boundary keys.
     */
    @Test
    void matchesTheFunctionOnAPrimaryKeyRun() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        String functionLabel = uniqueLabel("committing-parity-function");
        String committingLabel = uniqueLabel("committing-parity-procedure");

        try (Connection connection = openTestConnection()) {
            callFunction(connection, bigint(functionLabel, 3));
            assertEquals(11, payloadCount(TEST_BIGINT, "x"), "the function processes every row once");
            dsl.update(TEST_BIGINT).set(TEST_BIGINT.PAYLOAD, (String) null).execute();

            callCommitting(connection, bigint(committingLabel, 3));
        }

        assertEquals(11, payloadCount(TEST_BIGINT, "x"), "every row is processed exactly once");
        assertSameRunShape(functionLabel, committingLabel);
        assertTrue(runCompleted(committingLabel), "the run completes");
    }

    /**
     * An ordered-key run processes every row exactly once and cuts the same
     * boundaries as the function.
     */
    @Test
    void matchesTheFunctionOnAnOrderedKeyRun() throws Exception {
        seedOrderedKey();
        String functionLabel = uniqueLabel("committing-ordered-function");
        String committingLabel = uniqueLabel("committing-ordered-procedure");
        Call function = orderedKey(functionLabel);

        try (Connection connection = openTestConnection()) {
            callFunction(connection, function);
            assertEquals(5, orderedKeyMarked(), "the function processes every row once");
            dsl.update(TEST_ORDERED_KEY).set(TEST_ORDERED_KEY.PAYLOAD, (String) null).execute();

            callCommitting(connection, orderedKey(committingLabel));
        }

        assertEquals(5, orderedKeyMarked(), "every row is processed exactly once");
        assertSameRunShape(functionLabel, committingLabel);
        assertEquals(ORDERED_KEY_INDEX, dsl.select(MIGRATION_RUN.ORDERED_KEY_INDEX).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(committingLabel)).fetchOne(MIGRATION_RUN.ORDERED_KEY_INDEX));
    }

    /**
     * A blocks run processes every row exactly once.
     */
    @Test
    void processesABlocksRun() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6, 7);
        String label = uniqueLabel("committing-blocks");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, bigint(label, 1).chunkBy("blocks"));
        }

        assertEquals(7, payloadCount(TEST_BIGINT, "x"), "every row is processed exactly once");
        assertTrue(runCompleted(label), "the run completes");
    }

    /**
     * With several workers in flight the procedure still processes every row
     * exactly once and completes the run.
     */
    @Test
    void processesEveryRowOnceWithSeveralThreads() throws Exception {
        for (long id = 1; id <= 40; id++) {
            seedBigint(id);
        }
        String label = uniqueLabel("committing-threads");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, bigint(label, 3).threads(3));
        }

        assertEquals(40, payloadCount(TEST_BIGINT, "x"), "every row is processed exactly once");
        long runId = runId(label);
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
        assertTrue(runCompleted(runId));
    }

    /**
     * A partition-wise run calculates every leaf, then processes each chunk
     * against its leaf, with the function's run shape.
     */
    @Test
    void matchesTheFunctionOnAPartitionWiseRun() throws Exception {
        for (long id : List.of(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L)) {
            dsl.insertInto(TEST_PARTITIONED_RANGE, TEST_PARTITIONED_RANGE.ID).values(id).execute();
        }
        String functionLabel = uniqueLabel("committing-pw-function");
        String committingLabel = uniqueLabel("committing-pw-procedure");

        try (Connection connection = openTestConnection()) {
            callFunction(connection, partitioned(functionLabel));
            assertEquals(8, partitionedMarked(), "the function processes every row once");
            dsl.update(TEST_PARTITIONED_RANGE).set(TEST_PARTITIONED_RANGE.PAYLOAD, (String) null).execute();

            callCommitting(connection, partitioned(committingLabel));
        }

        assertEquals(8, partitionedMarked(), "every row is processed exactly once");
        assertSameRunShape(functionLabel, committingLabel);
        assertTrue(dsl.select(MIGRATION_RUN.PARTITION_WISE).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(committingLabel)).fetchOne(MIGRATION_RUN.PARTITION_WISE));
    }

    /**
     * Terminating the coordinator's backend mid-run leaves the run resumable: a
     * second call with the label finishes it, and no row is processed twice.
     */
    @Test
    void resumesAfterTheCoordinatorBackendIsTerminated() throws Exception {
        for (long id = 1; id <= 24; id++) {
            seedBigint(id);
        }
        String label = uniqueLabel("committing-terminate");
        Call slow = bigint(label, 2).sleepMs(200);
        int coordinatorPid;

        CompletableFuture<Void> first;
        try (Connection coordinator = openTestConnection()) {
            coordinatorPid = backendPid(coordinator);
            first = CompletableFuture.runAsync(() -> {
                try {
                    callCommitting(coordinator, slow);
                } catch (SQLException expected) {
                    throw new IllegalStateException(expected);
                }
            });

            awaitCompletedChunks(label, 3);
            assertEquals(Boolean.TRUE, dsl.fetchOne("SELECT pg_terminate_backend(?)", coordinatorPid)
                    .get(0, Boolean.class), "the coordinator backend is signalled");

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> first.get(30, TimeUnit.SECONDS));
            assertInstanceOf(SQLException.class, failure.getCause().getCause(),
                    "the terminated call fails with a SQL error");
            assertEquals("57P01", ((SQLException) failure.getCause().getCause()).getSQLState(),
                    "admin_shutdown is the terminated backend's SQLSTATE");
        }
        awaitNoChunkWorkers();
        long runId = runId(label);
        assertFalse(runCompleted(runId), "the terminated run is not complete");
        assertTrue(totalChunksCompleted(runId) < totalChunks(runId),
                "the terminated run stopped part-way");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, bigint(label, 2));
        }

        assertEquals(24, payloadCount(TEST_BIGINT, "x"), "every row is processed exactly once");
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    /**
     * A failing chunk is recorded in migration_error and its SQLSTATE is raised,
     * as the function does, and the run is left incomplete.
     */
    @Test
    void recordsAFailedChunkAndRaisesItsError() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("committing-failure");

        try (Connection connection = openTestConnection()) {
            SQLException failure = assertThrows(SQLException.class, () -> callCommitting(connection,
                    Call.of(FAILING_TEMPLATE, schema(), name(), label, 2)));
            assertEquals("22012", failure.getSQLState(), "division_by_zero is re-raised");
        }

        long runId = runId(label);
        assertFalse(runCompleted(runId), "a failed run is not complete");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "the failure is recorded once");
    }

    /**
     * Cancelling a running run stops the procedure, cancels the remaining
     * boundaries and leaves the run terminal, as the function does.
     */
    @Test
    void stopsWhenTheRunIsCancelled() throws Exception {
        for (long id = 1; id <= 30; id++) {
            seedBigint(id);
        }
        String label = uniqueLabel("committing-cancel");

        CompletableFuture<Void> call;
        try (Connection connection = openTestConnection()) {
            call = CompletableFuture.runAsync(() -> {
                try {
                    callCommitting(connection, bigint(label, 2).sleepMs(300));
                } catch (SQLException error) {
                    throw new IllegalStateException(error);
                }
            });
            awaitCompletedChunks(label, 2);

            assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label),
                    "the active run is cancelled");
            call.get(30, TimeUnit.SECONDS);
        }

        long runId = runId(label);
        assertNotNull(dsl.select(MIGRATION_RUN.CANCELLED_AT).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId)).fetchOne(MIGRATION_RUN.CANCELLED_AT));
        assertFalse(runCompleted(runId), "a cancelled run is not complete");
        assertTrue(totalChunksCompleted(runId) < totalChunks(runId), "the run stopped part-way");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNull())
                        .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "every unfinished boundary is marked cancelled");

        try (Connection connection = openTestConnection()) {
            SQLException again = assertThrows(SQLException.class,
                    () -> callCommitting(connection, bigint(label, 2)));
            assertEquals("55000", again.getSQLState(),
                    "a call on a cancelled run is rejected as it is for the function");
        }
    }

    /**
     * Pausing a running run stops the procedure once the in-flight chunks have
     * committed and leaves the run resumable: a call on the paused run does
     * nothing, and after the resume a call finishes it with every row processed
     * once.
     */
    @Test
    void stopsWhenTheRunIsPausedAndFinishesAfterTheResume() throws Exception {
        for (long id = 1; id <= 30; id++) {
            seedBigint(id);
        }
        String label = uniqueLabel("committing-pause");

        CompletableFuture<Void> call;
        try (Connection connection = openTestConnection()) {
            call = CompletableFuture.runAsync(() -> {
                try {
                    callCommitting(connection, bigint(label, 2).sleepMs(300));
                } catch (SQLException error) {
                    throw new IllegalStateException(error);
                }
            });
            awaitCompletedChunks(label, 2);

            assertNotNull(Routines.pauseMigrationRun(dsl.configuration(), label),
                    "the active run is paused");
            call.get(30, TimeUnit.SECONDS);
        }

        long runId = runId(label);
        assertNotNull(dsl.select(MIGRATION_RUN.PAUSED_AT).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId)).fetchOne(MIGRATION_RUN.PAUSED_AT),
                "the run is paused");
        assertFalse(runCompleted(runId), "a paused run is not complete");
        long completedWhenPaused = totalChunksCompleted(runId);
        assertTrue(completedWhenPaused < totalChunks(runId), "the run stopped part-way");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull())),
                "a pause cancels no boundary");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, bigint(label, 2));
        }
        assertEquals(completedWhenPaused, totalChunksCompleted(runId),
                "a call on a paused run does nothing until it is resumed");

        assertEquals(runId, Routines.resumeMigrationRun(dsl.configuration(), label));
        try (Connection connection = openTestConnection()) {
            callCommitting(connection, bigint(label, 2));
        }

        assertEquals(30, payloadCount(TEST_BIGINT, "x"), "every row is processed exactly once");
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    /**
     * A transient failure (here a serialization failure) is retried within the
     * run's budget, as the function does: the chunk runs twice, the run
     * completes, and no error is recorded.
     */
    @Test
    void retriesATransientFailureAndCompletes() throws Exception {
        seedBigint(1);
        String label = uniqueLabel("committing-retry");
        createRetryProbe();
        try (Connection connection = openTestConnection()) {
            callCommitting(connection, Call.of(probeTemplate("committing_retry_once", "40001"),
                    schema(), name(), label, 2).retries(3, 0));
        } finally {
            dropRetryProbe();
        }

        long runId = runId(label);
        assertTrue(runCompleted(runId), "the transient failure is retried and the run completes");
        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "a recovered retry records no error");
    }

    /**
     * When the retry budget is exhausted the failure is recorded once and its
     * SQLSTATE is raised, as the function does; the run is left incomplete.
     */
    @Test
    void recordsAnExhaustedTransientFailureOnce() throws Exception {
        seedBigint(1);
        String label = uniqueLabel("committing-retry-exhausted");
        createRetryProbe();
        try (Connection connection = openTestConnection()) {
            SQLException failure = assertThrows(SQLException.class, () -> callCommitting(connection,
                    Call.of(probeTemplate("committing_fail_counted", "40P01"),
                            schema(), name(), label, 2).retries(1, 0)));
            assertEquals("40P01", failure.getSQLState(), "the exhausted failure is re-raised");
        } finally {
            dropRetryProbe();
        }

        long runId = runId(label);
        assertFalse(runCompleted(runId), "an exhausted run is not complete");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "the failure is recorded once, after the budget is spent");
    }

    /**
     * A session whose transactions are not READ COMMITTED is rejected, as it is
     * for the function: the procedure's first COMMIT starts a transaction at the
     * session's default isolation, and the coordinator refuses it with
     * feature_not_supported before any run exists.
     */
    @Test
    void rejectsASessionThatIsNotReadCommitted() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("committing-isolation");

        try (Connection connection = openTestConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET default_transaction_isolation = 'repeatable read'");
            SQLException failure = assertThrows(SQLException.class,
                    () -> callCommitting(connection, bigint(label, 2)));
            assertEquals("0A000", failure.getSQLState(), "feature_not_supported");
        }

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "no run is created by a call the isolation level rejects");
        assertEquals(0, payloadCount(TEST_BIGINT, "x"), "no row is processed");
    }

    /**
     * The procedure cannot run inside a transaction block: the call fails with
     * invalid_transaction_termination before any run exists, so the misplaced
     * call changes nothing.
     */
    @Test
    void failsInsideATransactionBlockBeforeCreatingARun() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("committing-in-transaction");

        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class,
                    () -> callCommitting(connection, bigint(label, 2)));
            assertEquals("2D000", failure.getSQLState(), "invalid_transaction_termination");
            connection.rollback();
        }

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "no run is created by a call that cannot commit");
        assertEquals(0, payloadCount(TEST_BIGINT, "x"), "no row is processed");
    }

    /**
     * The procedure cannot be called from a function either: PostgreSQL ends the
     * call with invalid_transaction_termination before any run exists.
     */
    @Test
    void failsWhenCalledFromAFunction() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("committing-from-function");
        executeAsOwner("CREATE OR REPLACE FUNCTION dml_utils_fixtures.call_committing_from_function()"
                + " RETURNS void LANGUAGE plpgsql AS $$ BEGIN"
                + " CALL dml_utils.run_migration_chunks_committing(" + bigint(label, 2).arguments() + ");"
                + " END; $$");
        // The owner's default ACL withholds EXECUTE on a new function from every other
        // role (004-default-privileges), so grant it to the test role: without it the
        // call stops at 42501 before the procedure is reached.
        executeAsOwner("GRANT EXECUTE ON FUNCTION dml_utils_fixtures.call_committing_from_function()"
                + " TO " + TEST_USER);
        try (Connection connection = openTestConnection();
             Statement statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> statement.execute("SELECT dml_utils_fixtures.call_committing_from_function()"));
            assertEquals("2D000", failure.getSQLState(), "invalid_transaction_termination");
        } finally {
            executeAsOwner("DROP FUNCTION IF EXISTS dml_utils_fixtures.call_committing_from_function()");
        }

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "no run is created by a call that cannot commit");
    }

    /**
     * The caller role may execute the procedure and PUBLIC may not.
     */
    @Test
    void isExecutableByTheCallerRoleOnly() {
        assertEquals(Boolean.TRUE, dsl.fetchOne(
                        "SELECT pg_catalog.has_function_privilege('dml_utils_caller',"
                                + " 'dml_utils.run_migration_chunks_committing'::regproc, 'EXECUTE')")
                .get(0, Boolean.class));
        assertEquals(Boolean.FALSE, dsl.fetchOne(
                        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_proc AS p,"
                                + " LATERAL pg_catalog.aclexplode(p.proacl) AS acl"
                                + " WHERE p.oid = 'dml_utils.run_migration_chunks_committing'::regproc"
                                + " AND acl.grantee = 0)")
                .get(0, Boolean.class), "PUBLIC holds no execute privilege");
    }

    /**
     * Creates the probe sequence and the two functions the retry tests run as
     * their chunk: one fails with the given SQLSTATE on its first execution only,
     * the other on every execution.
     */
    private void createRetryProbe() {
        dropRetryProbe();
        dsl.execute("CREATE SEQUENCE " + PROBE_SEQUENCE);
        dsl.execute("GRANT USAGE, SELECT ON SEQUENCE " + PROBE_SEQUENCE
                + " TO dml_utils_owner, dml_utils_caller");
        dsl.execute("CREATE FUNCTION public.committing_retry_once(p_code text) RETURNS void"
                + " LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   IF nextval('" + PROBE_SEQUENCE + "') = 1 THEN"
                + "     RAISE EXCEPTION 'transient' USING ERRCODE = p_code;"
                + "   END IF;"
                + " END $$");
        dsl.execute("CREATE FUNCTION public.committing_fail_counted(p_code text) RETURNS void"
                + " LANGUAGE plpgsql AS $$"
                + " BEGIN"
                + "   PERFORM nextval('" + PROBE_SEQUENCE + "');"
                + "   RAISE EXCEPTION 'transient' USING ERRCODE = p_code;"
                + " END $$");
    }

    private void dropRetryProbe() {
        dsl.execute("DROP FUNCTION IF EXISTS public.committing_retry_once(text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.committing_fail_counted(text)");
        dsl.execute("DROP SEQUENCE IF EXISTS " + PROBE_SEQUENCE);
    }

    private static String probeTemplate(String function, String code) {
        return "SELECT public." + function + "('" + code + "')"
                + " FROM <driving_table> WHERE <chunking_clause>";
    }

    private Call bigint(String label, int chunkSize) {
        return Call.of(TEMPLATE, schema(), name(), label, chunkSize);
    }

    private Call orderedKey(String label) {
        return Call.of(TEMPLATE, schema(TEST_ORDERED_KEY), name(TEST_ORDERED_KEY), label, 2)
                .chunkBy("ordered_key").index(ORDERED_KEY_INDEX);
    }

    private Call partitioned(String label) {
        return Call.of(TEMPLATE, "dml_utils_fixtures", "test_partitioned_range", label, 2)
                .partitionWise();
    }

    private int orderedKeyMarked() {
        return payloadCount(TEST_ORDERED_KEY, "x");
    }

    private int partitionedMarked() {
        return payloadCount(TEST_PARTITIONED_RANGE, "x");
    }

    /**
     * Asserts two runs have the same chunk counts and the same boundary keys, the
     * function-parity contract for run metadata.
     */
    private void assertSameRunShape(String expectedLabel, String actualLabel) {
        long expected = runId(expectedLabel);
        long actual = runId(actualLabel);
        assertEquals(totalChunks(expected), totalChunks(actual), "total chunks");
        assertEquals(totalChunksCompleted(expected), totalChunksCompleted(actual), "chunks completed");
        assertEquals(completedBoundaries(expected), completedBoundaries(actual), "completed boundaries");
        assertEquals(boundaryKeys(expected), boundaryKeys(actual), "boundary keys");
    }

    private List<String> boundaryKeys(long runId) {
        return dsl.fetch("SELECT boundary_id::text FROM dml_utils_data.migration_boundary"
                        + " WHERE run_id = ? ORDER BY boundary_no", runId)
                .getValues(0, String.class);
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Waits until the run for the label has completed at least the given number of
     * chunks.
     */
    private void awaitCompletedChunks(String label, long chunks) throws InterruptedException {
        for (int attempt = 0; attempt < 600; attempt++) {
            Long completed = dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED).from(MIGRATION_RUN)
                    .where(MIGRATION_RUN.LABEL.eq(label)).fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED);
            if (completed != null && completed >= chunks) {
                return;
            }
            Thread.sleep(50);
        }
        fail("the run did not complete " + chunks + " chunks");
    }

    /**
     * Waits until no chunk worker is left running, so a resumed run starts from
     * committed state.
     */
    private void awaitNoChunkWorkers() throws InterruptedException {
        for (int attempt = 0; attempt < 600; attempt++) {
            Integer running = dsl.fetchOne(
                            "SELECT count(*)::int FROM pg_catalog.pg_stat_activity"
                                    + " WHERE pid <> pg_backend_pid()"
                                    + " AND query LIKE '%dml_utils_lib.process_migration_chunk%'"
                                    + " AND state <> 'idle'")
                    .get(0, Integer.class);
            if (running != null && running == 0) {
                return;
            }
            Thread.sleep(50);
        }
        fail("a chunk worker is still running");
    }
}
