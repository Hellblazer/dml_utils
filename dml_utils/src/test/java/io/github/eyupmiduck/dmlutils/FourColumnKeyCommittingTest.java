package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.github.eyupmiduck.dmlutils.ChunksFixture.PARTITIONED;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.PARTITIONED_ORDERED_INDEX;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.PLAIN;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.PLAIN_ORDERED_INDEX;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.ROWS;
import static io.github.eyupmiduck.dmlutils.ChunksFixture.SCHEMA;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestChunksFour.TEST_CHUNKS_FOUR;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestChunksPartitioned.TEST_CHUNKS_PARTITIONED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils.run_migration_chunks_committing} over the four-column
 * nexus.chunks key (tenant_id text, collection text, chash bytea, embedding_model
 * text): every row is processed exactly once on a plain table and on a table
 * partitioned two levels, through the primary-key and ordered-key strategies,
 * and a run whose coordinator backend is terminated resumes without repeating a
 * completed chunk. {@link FourColumnKeyTest} covers the same key through
 * {@code run_migration_chunks}.
 */
class FourColumnKeyCommittingTest extends CommittingRunTestBase {

    private static final String APPEND =
            "UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x' WHERE <chunking_clause>";

    /**
     * Seeds both fixtures, so a test works on a table with a known population.
     */
    @BeforeEach
    void seed() {
        dsl.truncate(TEST_CHUNKS_FOUR).execute();
        dsl.truncate(TEST_CHUNKS_PARTITIONED).execute();
        ChunksFixture.seed(dsl, PLAIN);
        ChunksFixture.seed(dsl, PARTITIONED);
    }

    /**
     * A primary-key run processes every row exactly once with several workers.
     */
    @Test
    void processesAFourColumnPrimaryKeyExactlyOnce() throws Exception {
        String label = uniqueLabel("committing-four-primary");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, plain(label, 11).threads(3));
        }

        assertEveryRowDoneOnce(TEST_CHUNKS_FOUR, "primary key");
        long runId = runId(label);
        assertTrue(runCompleted(runId), "the run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    /**
     * An ordered-key run over the four-column index, bytea last, processes every
     * row exactly once.
     */
    @Test
    void processesAFourColumnOrderedKeyExactlyOnce() throws Exception {
        String label = uniqueLabel("committing-four-ordered");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, plain(label, 11).chunkBy("ordered_key").index(PLAIN_ORDERED_INDEX));
        }

        assertEveryRowDoneOnce(TEST_CHUNKS_FOUR, "ordered key");
        assertTrue(runCompleted(label), "the run completes");
        assertEquals(PLAIN_ORDERED_INDEX, dsl.select(MIGRATION_RUN.ORDERED_KEY_INDEX).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label)).fetchOne(MIGRATION_RUN.ORDERED_KEY_INDEX));
    }

    /**
     * A partition-wise run over the two-level tree processes every row exactly
     * once, for the primary key and for the ordered key.
     */
    @Test
    void processesATwoLevelPartitionedTablePartitionWise() throws Exception {
        String primary = uniqueLabel("committing-four-pw-primary");
        String ordered = uniqueLabel("committing-four-pw-ordered");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, partitioned(primary, 9).threads(2));
        }
        assertEveryRowDoneOnce(TEST_CHUNKS_PARTITIONED, "partition-wise primary key");
        assertTrue(runCompleted(primary), "the primary-key run completes");
        assertEquals(ChunksFixture.PARTITIONED_LEAVES.size(), dsl.fetchOne(
                "SELECT count(*) FROM dml_utils_data.migration_run_leaf AS l"
                        + " JOIN dml_utils_data.migration_run AS r ON r.run_id = l.run_id"
                        + " WHERE r.label = ?", primary).get(0, Integer.class), "one manifest row per leaf");

        dsl.update(TEST_CHUNKS_PARTITIONED).set(TEST_CHUNKS_PARTITIONED.PAYLOAD, (String) null).execute();
        try (Connection connection = openTestConnection()) {
            callCommitting(connection, partitioned(ordered, 9).chunkBy("ordered_key")
                    .index(PARTITIONED_ORDERED_INDEX));
        }
        assertEveryRowDoneOnce(TEST_CHUNKS_PARTITIONED, "partition-wise ordered key");
        assertTrue(runCompleted(ordered), "the ordered-key run completes");
    }

    /**
     * Terminating the coordinator's backend mid-run leaves a run over the
     * four-column key resumable: a second call with the label finishes it, and no
     * row is processed twice.
     */
    @Test
    void resumesAFourColumnRunAfterTheCoordinatorBackendIsTerminated() throws Exception {
        resumeAfterTermination("committing-four-terminate", plain("", 10), TEST_CHUNKS_FOUR);
    }

    /**
     * The same termination and resume over the two-level partitioned table.
     */
    @Test
    void resumesATwoLevelPartitionWiseRunAfterTheCoordinatorBackendIsTerminated() throws Exception {
        resumeAfterTermination("committing-four-pw-terminate", partitioned("", 10), TEST_CHUNKS_PARTITIONED);
    }

    private void resumeAfterTermination(String labelPrefix, Call template,
                                        org.jooq.Table<?> table) throws Exception {
        String label = uniqueLabel(labelPrefix);
        Call slow = withLabel(template, label).sleepMs(200);

        CompletableFuture<Void> first;
        try (Connection coordinator = openTestConnection()) {
            int coordinatorPid = backendPid(coordinator);
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
        assertTrue(payloadCount(table, "x") < ROWS, "the terminated run stopped part-way");

        try (Connection connection = openTestConnection()) {
            callCommitting(connection, withLabel(template, label));
        }

        assertEveryRowDoneOnce(table, "after the resume");
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    private static Call withLabel(Call call, String label) {
        return new Call(call.template(), call.schema(), call.table(), label, call.chunkSize(),
                call.threads(), call.chunkBy(), call.sleepMs(), call.index(), call.partition());
    }

    private static Call plain(String label, int chunkSize) {
        return Call.of(APPEND, SCHEMA, PLAIN, label, chunkSize);
    }

    private static Call partitioned(String label, int chunkSize) {
        return Call.of(APPEND, SCHEMA, PARTITIONED, label, chunkSize).partitionWise();
    }

    private void assertEveryRowDoneOnce(org.jooq.Table<?> table, String context) {
        assertEquals(ROWS, dsl.fetchCount(table), context + ": row count");
        assertEquals(ROWS, payloadCount(table, "x"),
                context + ": every row processed exactly once (a repeat would hold xx)");
    }
}
