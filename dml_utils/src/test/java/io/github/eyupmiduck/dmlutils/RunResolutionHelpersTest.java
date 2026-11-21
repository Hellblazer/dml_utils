package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ResumeMigrationRun;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the run-resolution helpers: active-run lookup, autonomous creation,
 * and resume validation of a primary-key snapshot, an ordered-key descriptor and
 * a blocks filepath.
 */
class RunResolutionHelpersTest extends PostgresTestBase {

    private static final String TEMPLATE = "SELECT 1";
    private static final String ORDERED_INDEX = "test_ordered_key_created_at_id_idx";

    /**
     * Clears fixtures and metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndBigint();
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
        assertNotNull(migrationRun(single).getBoundariesCalculatedAt());
        assertEquals(3, migrationRun(single).getMaxRetries(), "the single run stores the retry count");
        assertEquals(1000, migrationRun(single).getRetryBackoffMs(),
                "the single run stores the retry backoff");

        long partitioned = Routines.createMigrationRun(dsl.configuration(),
                "dml_utils_fixtures", "test_partitioned_range",
                uniqueLabel("create-partitioned"), TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, true, 3, 1000);

        assertTrue(migrationRun(partitioned).getPartitionWise());
        assertEquals(3, dsl.fetchCount(MIGRATION_RUN_LEAF,
                MIGRATION_RUN_LEAF.RUN_ID.eq(partitioned)));
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(partitioned)));
        assertEquals(3, migrationRun(partitioned).getMaxRetries(),
                "the partition-wise run stores the retry count");
        assertEquals(1000, migrationRun(partitioned).getRetryBackoffMs(),
                "the partition-wise run stores the retry backoff");
    }

    /**
     * A wait that is aborted (here by {@code pg_cancel_backend} on the waiter
     * only) cancels the worker, so its uncommitted run row rolls back and no
     * worker is left behind. Without the cancel the worker survives, and the
     * moment the driving table is released it commits a run the caller never
     * received, holding the label.
     */
    @Test
    void cancelledWaitCancelsTheWorkerAndLeavesNoRun() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("create-cancelled");
        String database = currentDatabase();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection admin = openAdminConnection(database);
             Connection caller = openTestConnection()) {
            // Hold the worker in populate_migration_boundaries' boundary scan.
            admin.setAutoCommit(false);
            try (Statement statement = admin.createStatement()) {
                statement.execute("LOCK TABLE " + schema(TEST_BIGINT) + "."
                        + name(TEST_BIGINT) + " IN ACCESS EXCLUSIVE MODE");
            }

            int callerPid = backendPid(caller);
            AtomicReference<String> sqlstate = new AtomicReference<>();
            Future<?> call = executor.submit(() -> {
                try {
                    Routines.createMigrationRun(
                            DSL.using(caller, SQLDialect.POSTGRES).configuration(),
                            schema(TEST_BIGINT), name(TEST_BIGINT), label, TEMPLATE, 2, 1,
                            ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
                } catch (DataAccessException e) {
                    sqlstate.set(e.sqlState());
                }
            });
            try {
                awaitWorker(label);
                cancelBackend(callerPid);

                call.get(10, TimeUnit.SECONDS);
                assertEquals("57014", sqlstate.get(),
                        "the aborted wait re-raises its own error");
                assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                        "the cancelled worker must not commit a run the caller never received");
                awaitNoWorker(label);
            } finally {
                admin.rollback();
                try {
                    call.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // The worker never appeared or the wait was not interrupted:
                    // unblock the waiter so the connection can close.
                    cancelBackend(callerPid);
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "the caller task finished");
        }
    }

    /**
     * Waits until the launched worker is visible in {@code pg_stat_activity},
     * bounded so a launch failure cannot hang the test.
     *
     * @param label the run label embedded in the launched SQL
     * @throws InterruptedException if the poll is interrupted
     */
    private void awaitWorker(String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (workerCount(label) == 0) {
            if (System.nanoTime() > deadline) {
                fail("the worker for " + label + " never started");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Waits (bounded) until no worker for the label remains: the cancel is
     * best-effort, so an immediate zero sample could catch the worker just before
     * it exits.
     *
     * @param label the run label embedded in the launched SQL
     * @throws InterruptedException if the poll is interrupted
     */
    private void awaitNoWorker(String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (workerCount(label) > 0) {
            if (System.nanoTime() > deadline) {
                fail("the worker for " + label + " was still running");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Counts the live {@code pg_background} workers whose current statement is
     * this run's boundary population.
     *
     * @param label the run label embedded in the launched SQL
     * @return the number of matching live workers
     */
    private int workerCount(String label) {
        return dsl.fetchOne("""
                SELECT count(*)
                FROM pg_catalog.pg_stat_activity
                WHERE backend_type = 'pg_background'
                  AND query LIKE '%populate_migration_boundaries%'
                  AND query LIKE ?
                """, "%" + label + "%").get(0, Integer.class);
    }

    /**
     * Returns a connection's backend pid.
     *
     * @param connection the connection to identify
     * @return its backend pid
     * @throws SQLException if the query fails
     */
    private int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rows.next(), "pg_backend_pid() returns a row");
            return rows.getInt(1);
        }
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
        MigrationRunRecord stored = migrationRun(runId);

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
        MigrationRunRecord stored = migrationRun(runId);

        ResumeMigrationRun resumed = Routines.resumeMigrationRun(dsl.configuration(),
                stored, schema(TEST_BIGINT), name(TEST_BIGINT), TEMPLATE, 1, 1,
                "t", ChunkingStrategy.primary_key, 0, null, false, 9, 500);

        assertEquals(7, resumed.getOEffectiveMaxRetries(), "the stored retries win");
        assertEquals(250, resumed.getOEffectiveRetryBackoffMs(), "the stored backoff wins");
    }

    /**
     * Creating a primary-key run snapshots the live key descriptor, so a resume
     * can compare it with the live key.
     */
    @Test
    void creationSnapshotsThePrimaryKey() {
        dsl.truncate(TEST_COMPOSITE_PK).execute();
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                "dml_utils_fixtures", "test_composite_pk", uniqueLabel("pk-snapshot"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

        MigrationRunRecord stored = migrationRun(runId);
        assertArrayEquals(new String[]{"a", "b"}, stored.getPrimaryKeyColumns());
        assertArrayEquals(new String[]{"bigint", "bigint"}, stored.getPrimaryKeyKinds());
    }

    /**
     * Recreating the primary key with a different column order would reinterpret
     * the stored boundary values, so a resume fails closed with {@code 55000}.
     *
     * @throws Exception if the fixture key cannot be changed or restored
     */
    @Test
    void resumeRejectsAChangedPrimaryKeyOrder() throws Exception {
        dsl.truncate(TEST_COMPOSITE_PK).execute();
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                "dml_utils_fixtures", "test_composite_pk", uniqueLabel("resume-pk-order"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        MigrationRunRecord stored = migrationRun(runId);

        executeAsOwner("ALTER TABLE dml_utils_fixtures.test_composite_pk"
                + " DROP CONSTRAINT test_composite_pk_pkey");
        try {
            executeAsOwner("ALTER TABLE dml_utils_fixtures.test_composite_pk"
                    + " ADD PRIMARY KEY (b, a)");
            assertSqlState("55000", () -> Routines.resumeMigrationRun(dsl.configuration(),
                    stored, "dml_utils_fixtures", "test_composite_pk", TEMPLATE, 1, 1,
                    "t", ChunkingStrategy.primary_key, 0, null, false, 3, 1000));
        } finally {
            executeAsOwner("ALTER TABLE dml_utils_fixtures.test_composite_pk"
                    + " DROP CONSTRAINT IF EXISTS test_composite_pk_pkey");
            executeAsOwner("ALTER TABLE dml_utils_fixtures.test_composite_pk"
                    + " ADD PRIMARY KEY (a, b)");
        }
    }

    /**
     * Dropping the primary key entirely also fails a resume closed with
     * {@code 55000}, rather than surfacing the resolver's {@code 22023}.
     *
     * @throws Exception if the fixture key cannot be dropped or restored
     */
    @Test
    void resumeRejectsADroppedPrimaryKey() throws Exception {
        dsl.truncate(TEST_COMPOSITE_PK).execute();
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                "dml_utils_fixtures", "test_composite_pk", uniqueLabel("resume-pk-drop"),
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        MigrationRunRecord stored = migrationRun(runId);

        executeAsOwner("ALTER TABLE dml_utils_fixtures.test_composite_pk"
                + " DROP CONSTRAINT test_composite_pk_pkey");
        try {
            assertSqlState("55000", () -> Routines.resumeMigrationRun(dsl.configuration(),
                    stored, "dml_utils_fixtures", "test_composite_pk", TEMPLATE, 1, 1,
                    "t", ChunkingStrategy.primary_key, 0, null, false, 3, 1000));
        } finally {
            executeAsOwner("ALTER TABLE dml_utils_fixtures.test_composite_pk"
                    + " ADD PRIMARY KEY (a, b)");
        }
    }

    /**
     * A stored ordered-key descriptor that no longer matches the live index fails
     * closed with {@code 55000}.
     */
    @Test
    void resumeRejectsAChangedOrderedDescriptor() {
        String label = uniqueLabel("resume-ordered");
        long runId = populate(label, ChunkingStrategy.ordered_key);
        MigrationRunRecord stored = migrationRun(runId);
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
        MigrationRunRecord stored = migrationRun(runId);
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

}
