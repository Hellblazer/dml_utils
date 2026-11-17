package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.LeafPartitionsRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.MigrationRunBoundaryKeyRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the partition-wise leaf-boundary engine: per-leaf starts are appended
 * in manifest order and flattened, a failed leaf keeps the earlier leaves
 * committed and resumable, empty leaves finalize to zero chunks, and the terminal
 * boundary and totals are written only at finalization.
 */
class PartitionBoundaryCalculationTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_partitioned_range";
    private static final String ORDERED_KEY_INDEX = "test_partitioned_range_ordered_key_idx";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Re-grants the mid-partition SELECT revoked by one test, so a failure that
     * skips its own finally cannot break later tests (the GRANT is idempotent).
     *
     * @throws Exception if the grant cannot be restored
     */
    @AfterEach
    void restoreMidPartitionGrant() throws Exception {
        executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID + " TO "
                + TEST_USER + ", dml_utils_caller");
    }

    /**
     * Empties the range fixture between tests.
     */
    @BeforeEach
    void reset() {
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * Starts are appended one leaf at a time, the middle empty leaf contributes
     * nothing, and the terminal boundary and totals appear only at finalization.
     */
    @Test
    void flattensLeafStartsAndFinalizes() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L, 200L, 201L, 202L);
        long runId = createRun(ChunkingStrategy.primary_key, 2);

        assertEquals(1, calculate(runId));
        assertEquals(List.of(0L, 1L, 2L), runBoundaryNumbers(runId));
        assertEquals(List.of(1, 1, 1), runBoundaryLeaves(runId));
        assertNotNull(leaf(runId, 1).getTerminalBoundaryId(),
                "a calculated non-empty leaf snapshots its high-water key");
        assertNull(migrationRun(runId).getBoundariesCalculatedAt(),
                "calculation must not finalize the run");

        assertSqlState("55000", () -> finalize(runId));

        assertEquals(2, calculate(runId));
        assertEquals(List.of(0L, 1L, 2L), runBoundaryNumbers(runId),
                "the empty leaf must not add a boundary");

        assertEquals(3, calculate(runId));
        assertNull(leaf(runId, 2).getTerminalBoundaryId(),
                "an empty leaf has no high-water key");
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), runBoundaryNumbers(runId));
        assertEquals(List.of(1, 1, 1, 3, 3), runBoundaryLeaves(runId));
        assertNull(calculate(runId), "every leaf is calculated");

        finalize(runId);

        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L), runBoundaryNumbers(runId),
                "finalization appends exactly one terminal boundary");
        assertEquals(List.of(1, 1, 1, 3, 3, 3), runBoundaryLeaves(runId));

        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getBoundariesCalculatedAt());
        assertEquals(5L, run.getTotalChunks());
        assertEquals(0L, run.getTotalChunksCompleted());

        MigrationBoundaryRecord terminal = boundaries(runId).getLast();
        assertEquals(5L, terminal.getBoundaryNo());
        assertEquals(202L, terminal.getBoundaryId().getBigintValues()[0],
                "the terminal is the last key of the last non-empty leaf");
        assertEquals(3, leaf(runId, 1).getBoundaryCount());
        assertEquals(0L, leaf(runId, 2).getBoundaryCount());
        assertEquals(2L, leaf(runId, 3).getBoundaryCount());

        // Both entry points are idempotent once finalized.
        finalize(runId);
        assertEquals(6, boundaries(runId).size());
        assertEquals(5L, migrationRun(runId).getTotalChunks());
        assertNull(calculate(runId));
    }

    /**
     * A pause after one leaf is calculated stops the coordinator before the next
     * leaf; resuming continues and finalizes.
     */
    @Test
    void pauseStopsBoundaryCalculationAndResumeContinues() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L, 200L, 201L, 202L);
        long runId = populatePartitionWise("calc-pause", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);
        String label = dsl.select(MIGRATION_RUN.LABEL)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.LABEL);

        assertEquals(1, calculate(runId), "one leaf is calculated before the pause");
        assertEquals("calculated", leafState(runId, 1));
        assertEquals("pending", leafState(runId, 2));

        assertNotNull(io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.pauseMigrationRun(
                dsl.configuration(), label));

        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        assertNull(migrationRun(runId).getBoundariesCalculatedAt(),
                "a paused calculation does not finalize");
        assertEquals("calculated", leafState(runId, 1),
                "the already calculated leaf stays committed");
        assertEquals("pending", leafState(runId, 2),
                "the next leaf stays pending");

        assertNotNull(io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.resumeMigrationRun(
                dsl.configuration(), label));
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        assertNotNull(migrationRun(runId).getBoundariesCalculatedAt(),
                "the resumed calculation finalizes");
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            assertEquals("calculated", leafState(runId, ordinal));
        }
    }

    /**
     * A pause after every leaf is already calculated still lets finalization
     * finish; the pause then applies to processing.
     */
    @Test
    void pauseAfterEveryLeafCalculatedStillFinalizes() {
        insertRangeRows(0L, 1L, 2L, 3L, 4L, 200L, 201L, 202L);
        long runId = populatePartitionWise("calc-pause-finalize", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);
        String label = dsl.select(MIGRATION_RUN.LABEL)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.LABEL);

        int calculated = 0;
        Integer ordinal;
        while ((ordinal = calculate(runId)) != null) {
            assertTrue(++calculated <= 100,
                    "leaf calculation did not terminate; last ordinal " + ordinal);
        }
        assertEquals(3, calculated, "every leaf is calculated once");
        assertNull(migrationRun(runId).getBoundariesCalculatedAt());
        assertNotNull(io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.pauseMigrationRun(
                dsl.configuration(), label));

        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);

        assertNotNull(migrationRun(runId).getBoundariesCalculatedAt(),
                "finalization wins when every leaf is already calculated");
        assertNotNull(migrationRun(runId).getPausedAt(),
                "the pause remains until resume clears it");
    }

    /**
     * Every leaf being empty finalizes the run with no terminal boundary and zero
     * chunks.
     */
    @Test
    void emptyLeavesFinalizeWithZeroChunks() {
        long runId = createRun(ChunkingStrategy.primary_key, 4);

        assertEquals(1, calculate(runId));
        assertEquals(2, calculate(runId));
        assertEquals(3, calculate(runId));
        assertNull(calculate(runId));
        assertTrue(boundaries(runId).isEmpty());

        finalize(runId);

        assertTrue(boundaries(runId).isEmpty(), "no chunk means no terminal boundary");
        assertNotNull(migrationRun(runId).getBoundariesCalculatedAt());
        assertEquals(0L, migrationRun(runId).getTotalChunks());
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            assertNull(leaf(runId, ordinal).getTerminalBoundaryId());
        }
    }

    /**
     * A blocks run flattens one block start per leaf (chunk size one) and takes
     * the terminal from the last non-empty leaf's one-past-end block.
     */
    @Test
    void blocksRunFlattensPerLeafBlockStarts() {
        insertRangeRows(0L, 100L, 200L);
        long runId = createRun(ChunkingStrategy.blocks, 1);

        assertEquals(1, calculate(runId));
        assertEquals(2, calculate(runId));
        assertEquals(3, calculate(runId));
        finalize(runId);

        assertEquals(List.of(0L, 1L, 2L, 3L), runBoundaryNumbers(runId));
        assertEquals(List.of(1, 2, 3, 3), runBoundaryLeaves(runId));
        assertEquals(3L, migrationRun(runId).getTotalChunks());

        MigrationBoundaryRecord terminal = boundaries(runId).getLast();
        assertEquals(1L, terminal.getBoundaryId().getBigintValues()[0],
                "one row fits in one block, so the terminal is block 1");
    }

    /**
     * An ordered-key run chunks each leaf with the descriptor stored with the
     * run, not a re-resolution from the leaf.
     */
    @Test
    void orderedKeyRunUsesTheStoredDescriptor() {
        insertRangeRows(0L, 1L, 2L, 3L, 200L, 201L);
        long runId = createRun(ChunkingStrategy.ordered_key, 3);

        assertEquals(1, calculate(runId));
        assertEquals(2, calculate(runId));
        assertEquals(3, calculate(runId));
        finalize(runId);

        // low: ceil(4 / 3) = 2 starts; high: ceil(2 / 3) = 1 start.
        assertEquals(3L, migrationRun(runId).getTotalChunks());
        assertEquals(List.of(0L, 1L, 2L, 3L), runBoundaryNumbers(runId));
        assertEquals(List.of(1, 1, 3, 3), runBoundaryLeaves(runId));
    }

    /**
     * A detached leaf fails the calculation closed; the earlier leaves keep their
     * committed boundaries, and reattaching the leaf lets the run resume at that
     * leaf without recomputing the earlier one.
     */
    @Test
    void changedLeafFailsClosedAndResumesAfterReattach() throws Exception {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L);
        long runId = createRun(ChunkingStrategy.primary_key, 2);
        assertEquals(1, calculate(runId));
        long firstLeafBoundaries = boundaries(runId).size();

        executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_range"
                + " DETACH PARTITION " + SCHEMA + ".test_partitioned_range_mid");
        try {
            assertSqlState("55000", () -> calculate(runId));
        } finally {
            restoreMidPartition();
        }

        assertEquals(firstLeafBoundaries, boundaries(runId).size(),
                "a failed leaf must not disturb the committed boundary prefix");
        assertNull(migrationRun(runId).getBoundariesCalculatedAt());

        assertEquals(2, calculate(runId), "resume starts at the failed leaf");
        assertEquals(firstLeafBoundaries + 1, boundaries(runId).size());
        assertEquals(3, calculate(runId));
        finalize(runId);
        assertEquals(4L, migrationRun(runId).getTotalChunks());
    }

    /**
     * A leaf the callers cannot read fails before anything is appended and stays
     * resumable after the grant is restored.
     */
    @Test
    void missingLeafPrivilegeFailsBeforeAppendingAndResumes() throws Exception {
        insertRangeRows(0L, 1L, 100L, 101L, 200L, 201L);
        long runId = createRun(ChunkingStrategy.primary_key, 1);
        assertEquals(1, calculate(runId));
        long firstLeafBoundaries = boundaries(runId).size();

        executeAsOwner("REVOKE SELECT ON " + SCHEMA + ".test_partitioned_range_mid"
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> calculate(runId));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + SCHEMA + ".test_partitioned_range_mid TO "
                    + TEST_USER + ", dml_utils_caller");
        }

        assertEquals(firstLeafBoundaries, boundaries(runId).size());
        assertEquals(2, calculate(runId));
        assertEquals(3, calculate(runId));

        // The terminal scan also runs as the definer, so finalization must check
        // the caller's privilege on the last non-empty leaf too.
        executeAsOwner("REVOKE SELECT ON " + SCHEMA + ".test_partitioned_range_high"
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> finalize(runId));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + SCHEMA + ".test_partitioned_range_high TO "
                    + TEST_USER + ", dml_utils_caller");
        }

        finalize(runId);
        assertEquals(6L, migrationRun(runId).getTotalChunks());

        MigrationRunBoundaryKeyRecord key = Routines.migrationRunBoundaryKey(
                dsl.configuration(), runId).getFirst();
        assertArrayEquals(new String[]{"id"}, key.getOKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, key.getOKeyKinds());
    }

    /**
     * A missing run, a non-partition-wise run and a partition-wise blocks run's
     * null key all behave as documented.
     */
    @Test
    void rejectsInvalidRunsAndResolvesTheBlocksKeyAsNull() {
        assertSqlState("P0002", () -> calculate(-1L));
        assertSqlState("P0002", () -> finalize(-1L));

        Long plainRun = dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT,
                        MIGRATION_RUN.CHUNK_SIZE, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.STARTED_AT)
                .values(uniqueLabel("plain"), TEMPLATE, 1, 1, SCHEMA, TABLE,
                        OffsetDateTime.now())
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);

        assertSqlState("22023", () -> calculate(plainRun));
        assertSqlState("22023", () -> finalize(plainRun));

        insertRangeRows(0L);
        long blocksRun = createRun(ChunkingStrategy.blocks, 1);
        MigrationRunBoundaryKeyRecord key = Routines.migrationRunBoundaryKey(
                dsl.configuration(), blocksRun).getFirst();
        assertNull(key.getOKeyColumns());
        assertNull(key.getOKeyKinds());
    }

    /**
     * The key resolver returns the parent primary key, the stored ordered-key
     * descriptor and NULL for blocks.
     */
    @Test
    void migrationRunBoundaryKeyResolvesPerStrategy() {
        long primaryRun = createRun(ChunkingStrategy.primary_key, 1);
        MigrationRunBoundaryKeyRecord primaryKey = Routines.migrationRunBoundaryKey(
                dsl.configuration(), primaryRun).getFirst();
        assertArrayEquals(new String[]{"id"}, primaryKey.getOKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, primaryKey.getOKeyKinds());

        long orderedRun = createRun(ChunkingStrategy.ordered_key, 1);
        MigrationRunBoundaryKeyRecord orderedKey = Routines.migrationRunBoundaryKey(
                dsl.configuration(), orderedRun).getFirst();
        assertArrayEquals(new String[]{"id"}, orderedKey.getOKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, orderedKey.getOKeyKinds());
    }

    /**
     * When the population and the terminal snapshot disagree (here the boundary
     * INSERTs are suppressed, so the leaf looks empty while its snapshot has a
     * high-water key), the calculation fails with a descriptive 55000 instead of
     * silently recording an empty leaf.
     */
    @Test
    void reportsAPopulationSnapshotMismatch() throws Exception {
        insertRangeRows(0L, 1L, 2L);
        long runId = createRun(ChunkingStrategy.primary_key, 2);

        executeAsOwner("CREATE OR REPLACE FUNCTION dml_utils_fixtures.suppress_boundary()"
                + " RETURNS trigger LANGUAGE plpgsql AS $fn$ BEGIN"
                + " IF NEW.run_id = " + runId + " THEN RETURN NULL; END IF;"
                + " RETURN NEW; END $fn$");
        executeAsOwner("CREATE TRIGGER suppress_boundary BEFORE INSERT"
                + " ON dml_utils_data.migration_boundary FOR EACH ROW"
                + " EXECUTE FUNCTION dml_utils_fixtures.suppress_boundary()");
        try {
            assertSqlState("55000", () -> calculate(runId));
        } finally {
            executeAsOwner("DROP TRIGGER IF EXISTS suppress_boundary"
                    + " ON dml_utils_data.migration_boundary");
            executeAsOwner("DROP FUNCTION IF EXISTS dml_utils_fixtures.suppress_boundary()");
        }

        assertNull(leaf(runId, 1).getCalculatedAt(),
                "the failed leaf transaction rolled back its state");
    }

    private long createRun(ChunkingStrategy chunkBy, int chunkSize) {
        boolean ordered = chunkBy == ChunkingStrategy.ordered_key;
        Long runId = dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.PARTITION_WISE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.ORDERED_KEY_INDEX,
                        MIGRATION_RUN.ORDERED_KEY_COLUMNS, MIGRATION_RUN.ORDERED_KEY_KINDS,
                        MIGRATION_RUN.STARTED_AT)
                .values(uniqueLabel("partition-wise"), TEMPLATE, chunkSize, chunkBy, true, 1,
                        SCHEMA, TABLE,
                        ordered ? ORDERED_KEY_INDEX : null,
                        ordered ? new String[]{"id"} : null,
                        ordered ? new String[]{"bigint"} : null,
                        OffsetDateTime.now())
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);

        for (LeafPartitionsRecord leaf : Routines.leafPartitions(
                dsl.configuration(), SCHEMA, TABLE, chunkBy)) {
            dsl.insertInto(MIGRATION_RUN_LEAF)
                    .columns(MIGRATION_RUN_LEAF.RUN_ID, MIGRATION_RUN_LEAF.LEAF_ORDINAL,
                            MIGRATION_RUN_LEAF.LEAF_SCHEMA_NAME, MIGRATION_RUN_LEAF.LEAF_NAME,
                            MIGRATION_RUN_LEAF.LEAF_OID, MIGRATION_RUN_LEAF.LEAF_BOUND,
                            MIGRATION_RUN_LEAF.RELATION_FILEPATH, MIGRATION_RUN_LEAF.STATE)
                    .values(runId, leaf.getLeafOrdinal(), leaf.getLeafSchemaName(),
                            leaf.getLeafName(), leaf.getLeafOid(), leaf.getLeafBound(),
                            leaf.getRelationFilepath(), "pending")
                    .execute();
        }
        return runId;
    }


    private Integer calculate(long runId) {
        return Routines.calculateMigrationLeaf(dsl.configuration(), runId);
    }

    private void finalize(long runId) {
        Routines.finalizeMigrationBoundaries(dsl.configuration(), runId);
    }


    private List<Long> runBoundaryNumbers(long runId) {
        return boundaries(runId).stream().map(MigrationBoundaryRecord::getBoundaryNo).toList();
    }

    private List<Integer> runBoundaryLeaves(long runId) {
        return boundaries(runId).stream().map(MigrationBoundaryRecord::getLeafOrdinal).toList();
    }


}
