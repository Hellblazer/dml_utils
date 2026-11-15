package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.TerminalLeafForRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the terminal-boundary helpers: the last non-empty leaf and its
 * high-water arrays are returned, an all-empty run has none, and appending the
 * terminal checks the leaf identity, privilege and snapshot.
 */
class TerminalBoundaryHelpersTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String TABLE = "test_partitioned_range";

    /**
     * Empties the fixture and migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * The last non-empty leaf in manifest order and its high-water key are
     * returned.
     */
    @Test
    void returnsTheLastNonEmptyLeaf() {
        insertRangeRows(0L, 1L, 2L, 200L, 201L, 202L);
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        calculateAll(runId);

        TerminalLeafForRun terminal = Routines.terminalLeafForRun(
                dsl.configuration(), runId);

        assertEquals(3, terminal.getOLeafOrdinal());
        assertEquals(PARTITION_SCHEMA, terminal.getOLeafSchemaName());
        assertEquals("test_partitioned_range_high", terminal.getOLeafName());
        assertEquals(202L, terminal.getOBigintValues()[0]);
    }

    /**
     * An all-empty run has no terminal leaf.
     */
    @Test
    void returnsNoLeafWhenEveryLeafIsEmpty() {
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        calculateAll(runId);

        assertNull(Routines.terminalLeafForRun(dsl.configuration(), runId)
                .getOLeafOrdinal());
    }

    /**
     * An unknown run raises {@code P0002}, while a run without a leaf manifest
     * raises {@code 55000} instead of looking like an all-empty run.
     */
    @Test
    void rejectsAnUnknownRunOrOneWithoutAManifest() {
        assertSqlState("P0002", () -> Routines.terminalLeafForRun(
                dsl.configuration(), -1L));

        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT
                        .getSchema().getName(),
                io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT.getName(),
                uniqueLabel("terminal-helpers-single"), "SELECT 1", 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        assertSqlState("55000", () -> Routines.terminalLeafForRun(
                dsl.configuration(), runId));
    }

    /**
     * Appending the terminal writes one boundary after the starts and returns its
     * boundary number.
     */
    @Test
    void appendsTheTerminalBoundary() {
        insertRangeRows(0L, 1L, 2L, 200L, 201L, 202L);
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        calculateAll(runId);
        TerminalLeafForRun terminal = Routines.terminalLeafForRun(
                dsl.configuration(), runId);

        long boundaryNo = Routines.appendTerminalBoundary(dsl.configuration(), runId,
                terminal.getOLeafOrdinal());

        assertEquals(4L, boundaryNo);
        MigrationBoundaryRecord boundary = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo))
                .fetchOne();
        assertEquals(terminal.getOLeafOrdinal(), boundary.getLeafOrdinal());
        assertEquals(202L, boundary.getBoundaryId().getBigintValues()[0]);
    }

    /**
     * A missing snapshot, a leaf ordinal that is not in the manifest and a leaf
     * the caller cannot read are rejected.
     */
    @Test
    void rejectsMissingSnapshotLeafAndPrivilege() throws Exception {
        insertRangeRows(0L, 200L);
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        calculateAll(runId);
        TerminalLeafForRun terminal = Routines.terminalLeafForRun(
                dsl.configuration(), runId);

        // The middle leaf is calculated but empty, so it has no terminal snapshot.
        assertSqlState("55000", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), runId,
                leafOrdinal(runId, PARTITIONED_RANGE_MID)));
        assertSqlState("55000", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), runId, 999));

        executeAsOwner("REVOKE SELECT ON " + PARTITION_SCHEMA
                + ".test_partitioned_range_high FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.appendTerminalBoundary(
                    dsl.configuration(), runId, terminal.getOLeafOrdinal()));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + PARTITION_SCHEMA + ".test_partitioned_range_high TO "
                    + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * A NULL run id or a NULL/non-positive ordinal is a caller argument error
     * ({@code 22023}), while an unknown run is {@code P0002}.
     */
    @Test
    void rejectsBadRunIdOrOrdinal() {
        assertSqlState("22023", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), null, 1));
        assertSqlState("22023", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), 1L, null));
        assertSqlState("22023", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), 1L, 0));
        assertSqlState("P0002", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), -1L, 1));
    }

    /**
     * A run with uncalculated leaves cannot be finalized by a direct call: the
     * milestone would freeze a short total_chunks and the remaining leaves would
     * never be calculated.
     */
    @Test
    void rejectsARunWithUncalculatedLeaves() {
        insertRangeRows(0L, 200L);
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 1, null);

        assertSqlState("55000", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), runId, 1));
    }

    /**
     * Only the run's last non-empty leaf can carry the terminal boundary; an
     * earlier leaf would leave the later leaves unprocessed behind a finalized
     * run.
     */
    @Test
    void rejectsANonTerminalOrdinal() {
        insertRangeRows(0L, 200L);
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 1, null);
        calculateAll(runId);

        assertSqlState("55000", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), runId, leafOrdinal(runId, "test_partitioned_range_low")));
    }

    /**
     * The function is the single-terminal gate: a repeat call is rejected, and a
     * cancelled run cannot gain a terminal boundary.
     */
    @Test
    void rejectsARepeatAppendAndACancelledRun() {
        insertRangeRows(0L, 200L);
        long runId = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        calculateAll(runId);
        TerminalLeafForRun terminal = Routines.terminalLeafForRun(
                dsl.configuration(), runId);

        Routines.appendTerminalBoundary(dsl.configuration(), runId,
                terminal.getOLeafOrdinal());
        assertNotNull(dsl.select(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT)
                        .from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.RUN_ID.eq(runId))
                        .fetchOne(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT),
                "the append records the milestone itself");
        assertEquals(boundaryNo(runId), totalChunks(runId),
                "the terminal boundary number is the chunk count");

        assertSqlState("55000", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), runId, terminal.getOLeafOrdinal()));

        long cancelledRun = populatePartitionWise("terminal-helpers", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 1, null);
        calculateAll(cancelledRun);
        TerminalLeafForRun cancelledTerminal = Routines.terminalLeafForRun(
                dsl.configuration(), cancelledRun);
        Routines.markMigrationRunCancelled(dsl.configuration(), cancelledRun);
        assertSqlState("55000", () -> Routines.appendTerminalBoundary(
                dsl.configuration(), cancelledRun, cancelledTerminal.getOLeafOrdinal()));
    }

    private long boundaryNo(long runId) {
        Long max = dsl.fetchOne("SELECT max(boundary_no) FROM dml_utils_data.migration_boundary"
                        + " WHERE run_id = ?", runId)
                .get(0, Long.class);
        if (max == null) {
            throw new IllegalStateException("no boundaries for run " + runId);
        }
        return max;
    }


    private void calculateAll(long runId) {
        while (Routines.calculateNextLeafInWorker(dsl.configuration(), runId) != null) {
            // one leaf per worker transaction
        }
    }

}
