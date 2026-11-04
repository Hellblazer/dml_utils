package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
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
        long runId = createRun(2);

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
        long runId = createRun(1);
        assertEquals(1, calculateNext(runId));

        executeAsOwner("CREATE OR REPLACE FUNCTION " + PARTITION_SCHEMA + ".fail_leaf_two()"
                + " RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'injected leaf failure' USING ERRCODE = '55000';"
                + " RETURN NEW; END; $$");
        executeAsOwner("CREATE TRIGGER fail_leaf_two BEFORE INSERT"
                + " ON dml_utils_data.migration_boundary FOR EACH ROW"
                + " WHEN (NEW.leaf_ordinal = 2)"
                + " EXECUTE FUNCTION " + PARTITION_SCHEMA + ".fail_leaf_two()");
        try {
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
        long cancelled = createRun(1);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(cancelled))
                .execute();
        Routines.finalizeCalculatedRun(dsl.configuration(), cancelled);
        assertNull(run(cancelled).getBoundariesCalculatedAt());

        long finalized = createRun(1);
        for (int i = 0; i < 3; i++) {
            calculateNext(finalized);
        }
        Routines.finalizeCalculatedRun(dsl.configuration(), finalized);
        OffsetDateTime first = run(finalized).getBoundariesCalculatedAt();
        Routines.finalizeCalculatedRun(dsl.configuration(), finalized);
        assertEquals(first, run(finalized).getBoundariesCalculatedAt());
    }

    /**
     * Finalizing while a leaf is still pending raises {@code 55000}.
     */
    @Test
    void finalizeRejectsPendingLeaves() {
        long runId = createRun(1);
        assertSqlState("55000", () -> Routines.finalizeCalculatedRun(
                dsl.configuration(), runId));
    }

    private long createRun(int chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, TABLE, uniqueLabel("leaf-worker"),
                TEMPLATE, chunkSize, 1, ChunkingStrategy.primary_key, "t", 0, null, true);
    }

    private Integer calculateNext(long runId) {
        return Routines.calculateNextLeafInWorker(dsl.configuration(), runId);
    }

    private void insertRangeRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_PARTITIONED_RANGE,
                            TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(id, "payload-" + id)
                    .execute();
        }
    }

    private String leafState(long runId, int ordinal) {
        return leaf(runId, ordinal).getState();
    }

    private io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord leaf(
            long runId, int ordinal) {
        return dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .fetchOne();
    }

    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
