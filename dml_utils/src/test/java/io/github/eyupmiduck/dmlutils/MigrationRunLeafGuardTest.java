package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import org.jooq.TableField;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_data.check_migration_run_leaf} directly, without the
 * boundary routines: the trigger's run existence, run shape, closed-run and
 * per-leaf relation-filepath guards, and its manifest identity and terminal
 * snapshot rules.
 */
class MigrationRunLeafGuardTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String FIXTURE_TABLE = "test_bigint";

    /**
     * Asserts that a statement is rejected with the given SQLSTATE and a message
     * containing the given text, so the intended trigger branch (not a later
     * constraint) is what failed.
     *
     * @param sqlState    the expected SQLSTATE
     * @param messagePart text the error message must contain
     * @param statement   the statement to run
     */
    private static void assertRejected(String sqlState, String messagePart, Executable statement) {
        DataAccessException error = assertThrows(DataAccessException.class, statement::execute);
        assertEquals(sqlState, sqlState(error),
                () -> "unexpected SQLSTATE: " + error.getMessage());
        assertTrue(error.getMessage().contains(messagePart),
                () -> "unexpected message: " + error.getMessage());
    }

    /**
     * The terminal timestamps a run can be closed with.
     *
     * @return the terminal timestamp columns
     */
    private static Stream<TableField<MigrationRunRecord, OffsetDateTime>> terminalColumns() {
        return Stream.of(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT, MIGRATION_RUN.CANCELLED_AT,
                MIGRATION_RUN.COMPLETED_AT, MIGRATION_RUN.ARCHIVED_AT);
    }

    /**
     * Removes the runs (and, by cascade, their leaves) left by earlier tests.
     */
    @BeforeEach
    void resetRuns() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
    }

    /**
     * An open partition-wise run accepts its pending leaf.
     */
    @Test
    void acceptsAPendingLeafOnAnOpenRun() {
        long runId = insertRun(true, ChunkingStrategy.primary_key);

        insertLeaf(runId, 1, null);

        assertEquals(1, leafCount(runId));
    }

    /**
     * The trigger's own existence branch raises {@code 23503}, before the
     * foreign key could.
     */
    @Test
    void rejectsALeafForAMissingRun() {
        assertRejected("23503", "does not exist", () -> insertLeaf(-1L, 1, null));
    }

    /**
     * A leaf only belongs to a run chunked leaf by leaf.
     */
    @Test
    void rejectsALeafForANonPartitionWiseRun() {
        long runId = insertRun(false, ChunkingStrategy.primary_key);

        assertRejected("22023", "require a partition-wise run", () -> insertLeaf(runId, 1, null));
    }

    /**
     * A run that is finalized (boundaries calculated), cancelled, completed or
     * archived accepts no further leaf.
     *
     * @param terminalColumn the timestamp that makes the run terminal
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("terminalColumns")
    void rejectsALeafOnceTheRunIsTerminal(
            TableField<MigrationRunRecord, OffsetDateTime> terminalColumn) {
        long runId = insertRun(true, ChunkingStrategy.primary_key);
        closeRun(runId, terminalColumn);

        assertRejected("55000", "cannot add a leaf", () -> insertLeaf(runId, 1, null));
    }

    /**
     * A blocks run needs the per-leaf heap identity to detect a rewrite.
     */
    @Test
    void rejectsABlocksLeafWithoutARelationFilepath() {
        long runId = insertRun(true, ChunkingStrategy.blocks);

        assertRejected("22023", "must record its relation filepath",
                () -> insertLeaf(runId, 1, null));
    }

    /**
     * The other strategies keep the leaf-level filepath unset, so a stray value
     * cannot be carried into a resume.
     */
    @Test
    void rejectsARelationFilepathOnANonBlocksLeaf() {
        long runId = insertRun(true, ChunkingStrategy.primary_key);

        assertRejected("22023", "only a partition-wise blocks leaf",
                () -> insertLeaf(runId, 1, "base/1"));
    }

    /**
     * Every snapshotted identity column is immutable, including the manifest
     * ordering ({@code leaf_ordinal}) and the run the leaf belongs to.
     */
    @Test
    void rejectsChangesToTheManifestIdentity() {
        long runId = insertRun(true, ChunkingStrategy.primary_key);
        long otherRunId = insertRun(true, ChunkingStrategy.primary_key);
        insertLeaf(runId, 1, null);

        for (String assignment : List.of(
                "leaf_ordinal = leaf_ordinal + 100",
                "leaf_schema_name = leaf_schema_name || '_x'",
                "leaf_name = leaf_name || '_x'",
                "leaf_oid = 900002::oid",
                "leaf_bound = 'FOR VALUES IN (99)'",
                "created_at = created_at - interval '1 hour'",
                "run_id = " + otherRunId)) {
            assertRejected("22023", "identity is immutable", () -> dsl.execute(
                    "UPDATE dml_utils_data.migration_run_leaf SET " + assignment
                            + " WHERE run_id = ? AND leaf_ordinal = 1", runId));
        }
    }

    /**
     * A blocks leaf's filepath is part of the identity, so a stale heap pointer
     * cannot be replaced to pass the resume check.
     */
    @Test
    void rejectsAChangedBlocksRelationFilepath() {
        long runId = insertRun(true, ChunkingStrategy.blocks);
        insertLeaf(runId, 1, "base/1");

        assertRejected("22023", "identity is immutable", () -> dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.RELATION_FILEPATH, "base/2")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());
    }

    /**
     * The high-water snapshot may be written once with the calculated state and
     * is frozen afterwards.
     */
    @Test
    void freezesTheTerminalSnapshotOnceSet() {
        long runId = insertRun(true, ChunkingStrategy.primary_key);
        insertLeaf(runId, 1, null);

        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculated")
                .set(MIGRATION_RUN_LEAF.BOUNDARY_COUNT, 1L)
                .set(MIGRATION_RUN_LEAF.CALCULATED_AT, OffsetDateTime.now())
                .set(MIGRATION_RUN_LEAF.TERMINAL_BOUNDARY_ID,
                        new MigrationKeyRecord(new Long[]{1L}, null, null, null, null))
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        assertRejected("22023", "terminal snapshot is immutable once set", () -> dsl.update(
                        MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.TERMINAL_BOUNDARY_ID,
                        new MigrationKeyRecord(new Long[]{2L}, null, null, null, null))
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());
    }

    /**
     * The closed-run rule is insert-only: recording a leaf failure after the run
     * was cancelled must still work, or the coordinator could not report why a
     * worker stopped.
     */
    @Test
    void allowsAFailedLeafUpdateAfterTheRunIsCancelled() {
        long runId = insertRun(true, ChunkingStrategy.primary_key);
        insertLeaf(runId, 1, null);
        closeRun(runId, MIGRATION_RUN.CANCELLED_AT);

        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "failed")
                .set(MIGRATION_RUN_LEAF.ERROR_SQLSTATE, "57014")
                .set(MIGRATION_RUN_LEAF.ERROR_MESSAGE, "cancelled while processing")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        assertEquals("failed", dsl.fetchValue(MIGRATION_RUN_LEAF.STATE,
                MIGRATION_RUN_LEAF.RUN_ID.eq(runId)
                        .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))));
    }

    /**
     * The leaf insert holds a share lock on the run row, so a concurrent
     * finalize/cancel (an update of the same row) waits for it instead of
     * slipping past the closed-run guard. The holder's transaction is rolled
     * back, so the run stays open.
     */
    @Test
    void holdsTheRunRowAgainstAConcurrentClose() throws Exception {
        long runId = insertRun(true, ChunkingStrategy.primary_key);

        try (Connection holder = openTestConnection();
             Connection closer = openTestConnection()) {
            holder.setAutoCommit(false);
            try (Statement insert = holder.createStatement()) {
                insert.execute("""
                        INSERT INTO dml_utils_data.migration_run_leaf
                            (run_id, leaf_ordinal, leaf_schema_name, leaf_name, leaf_oid,
                             leaf_bound)
                        VALUES (%d, 1, '%s', '%s_1', 100001::oid, 'FOR VALUES IN (1)')
                        """.formatted(runId, FIXTURE_SCHEMA, FIXTURE_TABLE));
            }

            try (Statement close = closer.createStatement()) {
                close.execute("SET lock_timeout = '1s'");
                SQLException error = assertThrows(SQLException.class, () -> close.execute(
                        "UPDATE dml_utils_data.migration_run"
                                + " SET cancelled_at = pg_catalog.clock_timestamp()"
                                + " WHERE run_id = " + runId));
                assertEquals("55P03", error.getSQLState(),
                        () -> "unexpected error: " + error.getMessage());
            } finally {
                holder.rollback();
            }
        }
    }

    /**
     * Inserts a run with the given shape, started so the terminal stamps can be
     * set on it.
     *
     * @param partitionWise whether the run is chunked leaf by leaf
     * @param chunkBy       the chunking strategy
     * @return the new run's id
     */
    private long insertRun(boolean partitionWise, ChunkingStrategy chunkBy) {
        Long id = dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.PARTITION_WISE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.STARTED_AT)
                .values("leaf-guard-" + UUID.randomUUID(), "SELECT 1", 10, chunkBy,
                        partitionWise, 1, FIXTURE_SCHEMA, FIXTURE_TABLE, OffsetDateTime.now())
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
        assertNotNull(id, "the run insert returned no id");
        return id;
    }

    /**
     * Inserts one manifest leaf directly, so the trigger sees it without the
     * routines that normally create the manifest.
     *
     * @param runId            the owning run
     * @param ordinal          the manifest ordinal
     * @param relationFilepath the blocks heap filepath, or {@code null}
     */
    private void insertLeaf(long runId, int ordinal, String relationFilepath) {
        dsl.insertInto(MIGRATION_RUN_LEAF,
                        MIGRATION_RUN_LEAF.RUN_ID, MIGRATION_RUN_LEAF.LEAF_ORDINAL,
                        MIGRATION_RUN_LEAF.LEAF_SCHEMA_NAME, MIGRATION_RUN_LEAF.LEAF_NAME,
                        MIGRATION_RUN_LEAF.LEAF_OID, MIGRATION_RUN_LEAF.LEAF_BOUND,
                        MIGRATION_RUN_LEAF.RELATION_FILEPATH)
                .values(runId, ordinal, FIXTURE_SCHEMA, FIXTURE_TABLE + "_" + ordinal,
                        100000L + ordinal, "FOR VALUES IN (1)", relationFilepath)
                .execute();
    }

    /**
     * Sets one of the run's terminal stamps.
     *
     * @param runId  the run id
     * @param column the terminal timestamp column to set
     */
    private void closeRun(long runId,
                          TableField<MigrationRunRecord, OffsetDateTime> column) {
        dsl.update(MIGRATION_RUN)
                .set(column, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * Counts the manifest leaves of a run.
     *
     * @param runId the run id
     * @return the leaf count
     */
    private int leafCount(long runId) {
        return dsl.fetchCount(MIGRATION_RUN_LEAF, MIGRATION_RUN_LEAF.RUN_ID.eq(runId));
    }
}
