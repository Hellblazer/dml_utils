package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.TerminalLeafForRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.terminal_leaf_for_run}: an unknown run raises
 * {@code P0002}, a run without a manifest raises {@code 55000}, a run whose
 * leaves are all empty returns all-NULL fields, and otherwise the last non-empty
 * leaf's ordinal, name and stored high-water key are returned.
 */
class TerminalLeafForRunTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Empties the migration metadata and the range fixture between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * An unknown run id is a missing run, not an empty result.
     */
    @Test
    void rejectsAnUnknownRun() {
        assertSqlState("P0002", () -> Routines.terminalLeafForRun(
                dsl.configuration(), 987654321L));
    }

    /**
     * A single-relation run has no leaf manifest, which is reported as
     * {@code 55000} rather than as "every leaf is empty".
     */
    @Test
    void rejectsARunWithoutALeafManifest() {
        long runId = Routines.populateMigrationBoundaries(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                uniqueLabel("terminal-leaf-single"), "SELECT 1", 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);

        assertSqlState("55000", () -> Routines.terminalLeafForRun(
                dsl.configuration(), runId));
    }

    /**
     * The last non-empty manifest leaf, in manifest order, provides the terminal
     * key: the mid leaf's stored high-water id is returned with its identity.
     */
    @Test
    void returnsLastNonEmptyLeafHighWaterKey() {
        insertRangeRows(0L, 1L, 150L);
        long runId = populatePartitionWise("terminal-leaf", PARTITIONED_RANGE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        TerminalLeafForRun terminal = Routines.terminalLeafForRun(
                dsl.configuration(), runId);
        MigrationRunLeafRecord last = dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.BOUNDARY_COUNT.gt(0L))
                .orderBy(MIGRATION_RUN_LEAF.LEAF_ORDINAL.desc())
                .limit(1)
                .fetchOne();
        assertNotNull(last, "the run should have a non-empty leaf");

        assertEquals(last.getLeafOrdinal(), terminal.getOLeafOrdinal());
        assertEquals(last.getLeafSchemaName(), terminal.getOLeafSchemaName());
        assertEquals(last.getLeafName(), terminal.getOLeafName());
        assertEquals(PARTITIONED_RANGE_MID, terminal.getOLeafName(),
                "the high rows live in the middle leaf");
        assertArrayEquals(last.getTerminalBoundaryId().getBigintValues(),
                terminal.getOBigintValues());
        assertEquals(150L, terminal.getOBigintValues()[0].longValue(),
                "the returned value is the leaf's stored high-water key");
        assertNull(terminal.getOTextValues());
    }

    /**
     * A run whose manifest exists but whose leaves are all empty has no terminal
     * to return, which is distinct from a missing manifest.
     */
    @Test
    void returnsAllNullFieldsWhenEveryLeafIsEmpty() {
        long runId = populatePartitionWise("terminal-leaf-empty", PARTITIONED_RANGE,
                TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        TerminalLeafForRun terminal = Routines.terminalLeafForRun(
                dsl.configuration(), runId);

        assertNull(terminal.getOLeafOrdinal());
        assertNull(terminal.getOLeafSchemaName());
        assertNull(terminal.getOLeafName());
        assertNull(terminal.getOBigintValues());
        assertNull(terminal.getOTextValues());
        assertNull(terminal.getOUuidValues());
        assertNull(terminal.getOTimestamptzValues());
    }
}
