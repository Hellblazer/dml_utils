package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the leaf-calculation worker helpers: one leaf is calculated per
 * transaction with the leaf state advanced, a failing leaf is recorded and
 * re-raised, and finalization writes the terminal boundary only when every leaf
 * is calculated.
 */
class LeafCalculationWorkerTest extends PartitionTestBase {

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
     * Each call calculates the next leaf in its own worker transaction; after the
     * last leaf the call returns NULL, and finalization writes the terminal.
     */
    @Test
    void calculatesEveryLeafThenFinalizes() {
        insertRangeRows(0L, 1L, 2L, 200L, 201L, 202L);
        long runId = populatePartitionWise("leaf-worker", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        assertEquals(1, calculateNext(runId));
        assertEquals(2, calculateNext(runId));
        assertEquals(3, calculateNext(runId));
        assertNull(calculateNext(runId));
        assertNull(run(runId).getBoundariesCalculatedAt(),
                "the calculation worker does not finalize the run");

        Routines.finalizeCalculatedRun(dsl.configuration(), runId);

        assertNotNull(run(runId).getBoundariesCalculatedAt());
        assertEquals(4L, run(runId).getTotalChunks());
        assertEquals("calculated", leafState(runId, 1));
        assertEquals("calculated", leafState(runId, 2));
        assertEquals("calculated", leafState(runId, 3));
    }

    /**
     * A failing leaf is recorded on the manifest in its own transaction and the
     * worker error is re-raised; the earlier leaf keeps its boundaries.
     */
    @Test
    void recordsAFailingLeafAndReraises() throws Exception {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L);
        long runId = populatePartitionWise("leaf-worker", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        assertEquals(1, calculateNext(runId));

        try {
            executeAsOwner("CREATE OR REPLACE FUNCTION " + PARTITION_SCHEMA + ".fail_leaf_two()"
                    + " RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " RAISE EXCEPTION 'injected leaf failure' USING ERRCODE = '55000';"
                    + " RETURN NEW; END; $$");
            executeAsOwner("CREATE TRIGGER fail_leaf_two BEFORE INSERT"
                    + " ON dml_utils_data.migration_boundary FOR EACH ROW"
                    + " WHEN (NEW.leaf_ordinal = 2)"
                    + " EXECUTE FUNCTION " + PARTITION_SCHEMA + ".fail_leaf_two()");
            assertSqlState("55000", () -> calculateNext(runId));
        } finally {
            executeAsOwner("DROP TRIGGER IF EXISTS fail_leaf_two"
                    + " ON dml_utils_data.migration_boundary");
            executeAsOwner("DROP FUNCTION IF EXISTS " + PARTITION_SCHEMA + ".fail_leaf_two()");
        }

        assertEquals("calculated", leafState(runId, 1));
        assertEquals("failed", leafState(runId, 2));
        assertEquals("55000", leaf(runId, 2).getErrorSqlstate());
        assertEquals("pending", leafState(runId, 3));
    }

    /**
     * Finalizing a cancelled or already-finalized run is a no-op.
     */
    @Test
    void finalizeIsANoOpForCancelledAndFinalizedRuns() {
        long cancelled = populatePartitionWise("leaf-worker", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(cancelled))
                .execute();
        Routines.finalizeCalculatedRun(dsl.configuration(), cancelled);
        assertNull(run(cancelled).getBoundariesCalculatedAt());

        long finalized = populatePartitionWise("leaf-worker", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        for (int i = 0; i < 3; i++) {
            calculateNext(finalized);
        }
        Routines.finalizeCalculatedRun(dsl.configuration(), finalized);
        OffsetDateTime first = run(finalized).getBoundariesCalculatedAt();
        Routines.finalizeCalculatedRun(dsl.configuration(), finalized);
        assertEquals(first, run(finalized).getBoundariesCalculatedAt());
    }

    /**
     * A failure inside the autonomous finalizer is re-raised to the caller
     * instead of being reported as a successful finalization.
     */
    @Test
    void reRaisesAFailingFinalizer() throws Exception {
        insertRangeRows(0L, 100L, 200L);
        long runId = populatePartitionWise("leaf-worker", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        for (int i = 0; i < 3; i++) {
            calculateNext(runId);
        }

        executeAsOwner("CREATE OR REPLACE FUNCTION " + PARTITION_SCHEMA + ".fail_finalize()"
                + " RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'injected finalize failure' USING ERRCODE = 'P0001';"
                + " RETURN NEW; END; $$");
        executeAsOwner("CREATE TRIGGER fail_finalize BEFORE INSERT"
                + " ON dml_utils_data.migration_boundary FOR EACH ROW"
                + " EXECUTE FUNCTION " + PARTITION_SCHEMA + ".fail_finalize()");
        try {
            assertSqlState("P0001", () -> Routines.finalizeCalculatedRun(
                    dsl.configuration(), runId));
        } finally {
            executeAsOwner("DROP TRIGGER IF EXISTS fail_finalize"
                    + " ON dml_utils_data.migration_boundary");
            executeAsOwner("DROP FUNCTION IF EXISTS " + PARTITION_SCHEMA + ".fail_finalize()");
        }

        assertNull(run(runId).getBoundariesCalculatedAt(),
                "a failed finalizer must not record the milestone");
    }

    /**
     * Finalizing an unknown run raises {@code P0002} instead of treating it as an
     * already-finalized no-op.
     */
    @Test
    void finalizeRejectsAMissingRun() {
        assertSqlState("P0002", () -> Routines.finalizeCalculatedRun(
                dsl.configuration(), -1L));
    }

    /**
     * Finalizing while a leaf is still pending raises {@code 55000}.
     */
    @Test
    void finalizeRejectsPendingLeaves() {
        long runId = populatePartitionWise("leaf-worker", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 1, null);
        assertSqlState("55000", () -> Routines.finalizeCalculatedRun(
                dsl.configuration(), runId));
    }


    private Integer calculateNext(long runId) {
        return Routines.calculateNextLeafInWorker(dsl.configuration(), runId);
    }


    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
