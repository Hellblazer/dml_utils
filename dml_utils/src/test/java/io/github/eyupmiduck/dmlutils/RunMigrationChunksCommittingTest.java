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
 * metadata, resume, partition-wise, error recording, cancellation) and differs
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
}
