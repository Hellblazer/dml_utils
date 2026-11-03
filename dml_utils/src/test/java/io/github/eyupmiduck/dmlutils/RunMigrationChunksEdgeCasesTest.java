package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.concurrent.*;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises coordinator edge cases that the ordinary happy/failure path tests
 * do not reach: a boundary claimed by another coordinator (P0C01), a failure to
 * record a chunk error, and an unexpected coordinator-side render error while a
 * worker is in flight.
 */
class RunMigrationChunksEdgeCasesTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";
    private static final String FAILING_TEMPLATE =
            "SELECT 1 / 0 FROM <driving_table> WHERE <chunking_clause>";
    private static final String SLOW_TEMPLATE =
            "SELECT pg_sleep(0.5) FROM <driving_table> WHERE <chunking_clause>";

    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A boundary that another coordinator has claimed while this coordinator was
     * launching it produces a worker P0C01. That is benign: the coordinator must
     * continue to the next boundary, record no error, and complete the run.
     *
     * <p>The test holds a row lock on the first boundary before starting the
     * coordinator, so the launched worker blocks while the other session finishes
     * the boundary. Releasing the lock makes the worker re-check the row under
     * READ COMMITTED and see {@code completed_at}, which is the real claim race.
     */
    @Test
    void treatsABoundaryClaimedByAnotherCoordinatorAsBenign() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6);
        String label = uniqueLabel("p0002");
        long runId = populate(label);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection blocker = openTestConnection()) {
            blocker.setAutoCommit(false);
            DSLContext blockerDsl = DSL.using(blocker, SQLDialect.POSTGRES);
            blockerDsl.fetchOne(
                    "SELECT 1 FROM dml_utils_data.migration_boundary"
                            + " WHERE run_id = ? AND boundary_no = 0 FOR UPDATE",
                    runId);

            Future<Void> run = pool.submit(runOnOwnConnection(label));
            awaitWorkerBlockedOnClaim();
            completeBoundary(blocker, blockerDsl, runId);

            run.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertTrue(runCompleted(runId), "the claim is benign and the run should complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "the benign claim must not be recorded as an error");
        assertEquals(3, completedBoundaries(runId),
                "the manually completed boundary and the two processed boundaries should be complete");
    }

    /**
     * If recording a chunk failure itself fails, the best-effort handler must
     * swallow the recording error and re-raise the original chunk SQLSTATE. The
     * test installs a trigger that fails every insert into migration_error so the
     * recording worker cannot record anything.
     */
    @Test
    void failureRecordingFailureDoesNotReplaceTheChunkError() throws Exception {
        seedBigint(1, 2, 3);
        installFailingErrorTrigger();
        try {
            assertSqlState("22012", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(
                    dsl.configuration(), FAILING_TEMPLATE, schema(), name(),
                    uniqueLabel("error-record-failure"), 2, 1, "t",
                    ChunkingStrategy.primary_key, 1, 0, null, false));

            assertEquals(0, dsl.fetchCount(MIGRATION_ERROR),
                    "the failed recording attempt must not leave a partial error row");
        } finally {
            dropFailingErrorTrigger();
        }
    }

    /**
     * An unexpected coordinator-side render failure must drain the worker that is
     * already in flight, then re-raise the render error. The run's first boundary
     * is valid and slow; the second boundary is reversed, so rendering its range
     * raises 22023 while the first worker is still running.
     */
    @Test
    void unexpectedRenderFailureDrainsTheInFlightWorker() throws Exception {
        seedBigint(1, 2, 3, 4);
        String label = uniqueLabel("render-drain");
        long runId = insertReversedRun(label);
        insertBoundary(runId, 0, 1L);
        insertBoundary(runId, 1, 5L);
        insertBoundary(runId, 2, 4L);

        assertSqlState("22023", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(
                dsl.configuration(), SLOW_TEMPLATE, schema(), name(), label, 4, 2, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false));

        assertTrue(boundaryCompleted(runId, 0),
                "the in-flight worker should finish and commit before the original error is re-raised");
        assertFalse(runCompleted(runId), "the coordinator-side failure must leave the run incomplete");
    }

    private Callable<Void> runOnOwnConnection(String label) {
        return () -> {
            try (Connection connection = openTestConnection()) {
                DSLContext context = DSL.using(connection, SQLDialect.POSTGRES);
                io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(context.configuration(), TEMPLATE, schema(), name(),
                        label, 2, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false);
                return null;
            }
        };
    }

    private long populate(String label) {
        return Routines.populateMigrationBoundaries(dsl.configuration(), schema(), name(), label,
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false);
    }

    private void completeBoundary(Connection blocker, DSLContext blockerDsl, long runId) throws Exception {
        blockerDsl.execute(
                "UPDATE dml_utils_data.migration_boundary"
                        + " SET started_at = clock_timestamp(), completed_at = clock_timestamp()"
                        + " WHERE run_id = ? AND boundary_no = 0",
                runId);
        blockerDsl.execute(
                "UPDATE dml_utils_data.migration_run"
                        + " SET total_chunks_completed = total_chunks_completed + 1"
                        + " WHERE run_id = ?",
                runId);
        blocker.commit();
    }

    private void awaitWorkerBlockedOnClaim() throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Integer blocked = dsl.fetchOne(
                            "SELECT count(*)::int FROM pg_catalog.pg_stat_activity"
                                    + " WHERE pid <> pg_backend_pid()"
                                    + " AND wait_event_type = 'Lock'"
                                    + " AND query LIKE '%dml_utils_lib.process_migration_chunk%'")
                    .get(0, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(50);
        }
        fail("the launched worker did not block on the already-claimed boundary");
    }

    private long insertReversedRun(String label) {
        OffsetDateTime now = OffsetDateTime.now();
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.DRIVING_TABLE_ALIAS,
                        MIGRATION_RUN.STARTED_AT, MIGRATION_RUN.BOUNDARIES_CALCULATED_AT,
                        MIGRATION_RUN.TOTAL_CHUNKS)
                .values(label, SLOW_TEMPLATE, 4, ChunkingStrategy.primary_key, 2, schema(), name(), "t",
                        now, now, 2L)
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    private void insertBoundary(long runId, long boundaryNo, long id) {
        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, boundaryNo,
                        new MigrationKeyRecord(new Long[]{id}, null, null, null))
                .execute();
    }

    private void installFailingErrorTrigger() throws Exception {
        try (Connection owner = openOwnerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION public.fail_error_record_for_test()"
                    + " RETURNS trigger LANGUAGE plpgsql AS $$"
                    + " BEGIN RAISE EXCEPTION 'forced error-recording failure' USING ERRCODE = 'P0001';"
                    + " END; $$");
            statement.execute("DROP TRIGGER IF EXISTS fail_error_record_for_test"
                    + " ON dml_utils_data.migration_error");
            statement.execute("CREATE TRIGGER fail_error_record_for_test"
                    + " BEFORE INSERT ON dml_utils_data.migration_error"
                    + " FOR EACH ROW EXECUTE FUNCTION public.fail_error_record_for_test()");
        }
    }

    private void dropFailingErrorTrigger() throws Exception {
        try (Connection owner = openOwnerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS fail_error_record_for_test"
                    + " ON dml_utils_data.migration_error");
            statement.execute("DROP FUNCTION IF EXISTS public.fail_error_record_for_test()");
        }
    }
}
