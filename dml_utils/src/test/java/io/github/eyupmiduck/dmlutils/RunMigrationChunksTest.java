package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.routines.RunMigrationChunks;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationErrorRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeThree.TEST_COMPOSITE_THREE;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestInteger.TEST_INTEGER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestKey.TEST_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOther.TEST_OTHER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestSmallint.TEST_SMALLINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestTimestamptzCompositePk.TEST_TIMESTAMPTZ_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestTimestamptzPk.TEST_TIMESTAMPTZ_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.run_migration_chunks}: it processes every fixed-row
 * chunk of the driving table via pg_background workers, records run and
 * boundary completion, resumes from the first unclaimed boundary, and is a
 * no-op for an already-completed run.
 */
class RunMigrationChunksTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Asserts a boundary key's position-aligned parts for the fixture's
     * {@code (b integer, a text, c uuid)} primary key.
     */
    private static void assertCompositeKey(MigrationKeyRecord key, long b, String a, UUID c) {
        assertEquals(b, key.getBigintValues()[0].longValue(), "key part b");
        assertEquals(a, key.getTextValues()[1], "key part a");
        assertEquals(c, key.getUuidValues()[2], "key part c");
    }

    /**
     * Asserts a boundary key's position-aligned parts for the fixture's
     * {@code (a bigint, b text)} primary key.
     */
    private static void assertMixedKey(MigrationKeyRecord key, long a, String b) {
        assertEquals(a, key.getBigintValues()[0].longValue(), "key part a");
        assertEquals(b, key.getTextValues()[1], "key part b");
    }

    /**
     * Clears the fixture tables this class drives a run over, so each test
     * starts from a known state.
     */
    @BeforeEach
    void resetFixtures() {
        for (Table<?> table : List.of(TEST_BIGINT, TEST_OTHER, TEST_INTEGER, TEST_SMALLINT,
                TEST_TEXT, TEST_UUID, TEST_KEY, TEST_COMPOSITE_MIXED, TEST_COMPOSITE_THREE,
                TEST_ORDERED_KEY, TEST_TIMESTAMPTZ_PK, TEST_TIMESTAMPTZ_COMPOSITE_PK)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * An ordered_key run chunks in the named index's order, stores the descriptor
     * and processes every row.
     */
    @Test
    void processesAnOrderedKeyRunByTheIndexOrder() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_ORDERED_KEY,
                            TEST_ORDERED_KEY.ID, TEST_ORDERED_KEY.CREATED_AT,
                            TEST_ORDERED_KEY.EXTERNAL_ID, TEST_ORDERED_KEY.LABEL,
                            TEST_ORDERED_KEY.COLLATED_LABEL, TEST_ORDERED_KEY.NUMERIC_KEY)
                    .values((long) i, timestamp((i + 1) / 2), uuid(i), "k" + i, "k" + i,
                            BigDecimal.valueOf(i))
                    .execute();
        }
        String label = label("ordered-key");

        runOrderedKey(label, 2, 2);

        long runId = runId(label);
        assertEquals(3L, totalChunks(runId));
        assertEquals(3L, totalChunksCompleted(runId));
        assertEquals(3, completedBoundaries(runId), "the three chunk starts complete");
        assertEquals(4, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)),
                "three starts plus the terminal boundary");
        assertTrue(runCompleted(runId), "the ordered-key run completes");
        assertEquals(5, dsl.fetchCount(TEST_ORDERED_KEY, TEST_ORDERED_KEY.PAYLOAD.eq("done")),
                "every row is processed");
        assertEquals("test_ordered_key_created_at_id_idx",
                dsl.select(MIGRATION_RUN.ORDERED_KEY_INDEX)
                        .from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.RUN_ID.eq(runId))
                        .fetchOne(MIGRATION_RUN.ORDERED_KEY_INDEX),
                "the run stores the index");
    }

    /**
     * A timestamptz primary key is chunked by instant with the primary_key
     * strategy.
     */
    @Test
    void processesATimestamptzPrimaryKey() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_TIMESTAMPTZ_PK, TEST_TIMESTAMPTZ_PK.CREATED_AT)
                    .values(timestamp(i))
                    .execute();
        }
        String label = label("timestamptz-pk");

        run(label, 2, TEST_TIMESTAMPTZ_PK);

        long runId = runId(label);
        assertEquals(3L, totalChunks(runId));
        assertEquals(3L, totalChunksCompleted(runId));
        assertTrue(runCompleted(runId), "the timestamptz primary-key run completes");
        assertEquals(5, dsl.fetchCount(TEST_TIMESTAMPTZ_PK,
                TEST_TIMESTAMPTZ_PK.PAYLOAD.eq("done")), "every row is processed");
    }

    /**
     * A timestamptz primary-key run resumes after a failed chunk.
     */
    @Test
    void resumesATimestamptzPrimaryKeyRun() {
        for (int i = 1; i <= 6; i++) {
            dsl.insertInto(TEST_TIMESTAMPTZ_PK, TEST_TIMESTAMPTZ_PK.CREATED_AT)
                    .values(timestamp(i))
                    .execute();
        }
        String label = label("timestamptz-resume");
        String failing = "UPDATE <driving_table> SET payload = CASE"
                + " WHEN created_at = '2026-01-01T00:00:03Z'"
                + " THEN (ascii(created_at::text) / 0)::text"
                + " ELSE 'done' END WHERE <chunking_clause>";

        assertSqlState("22012", () -> runWith(label, failing, 2, 1, TEST_TIMESTAMPTZ_PK));
        Routines.setMigrationRunSqlText(dsl.configuration(), label, TEMPLATE);
        run(label, 2, TEST_TIMESTAMPTZ_PK);

        long runId = runId(label);
        assertEquals(3L, totalChunks(runId));
        assertEquals(3L, totalChunksCompleted(runId));
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(6, dsl.fetchCount(TEST_TIMESTAMPTZ_PK,
                TEST_TIMESTAMPTZ_PK.PAYLOAD.eq("done")), "every row is processed");
    }

    /**
     * A composite-primary-key table is chunked and every row is processed.
     */
    @Test
    void processesACompositeKeyedTable() {
        dsl.insertInto(TEST_COMPOSITE_THREE, TEST_COMPOSITE_THREE.B, TEST_COMPOSITE_THREE.A,
                        TEST_COMPOSITE_THREE.C)
                .values(1, "x", uuid(1))
                .values(1, "y", uuid(2))
                .values(2, "x", uuid(3))
                .execute();

        String label = label("composite");
        run(label, 2, TEST_COMPOSITE_THREE);

        long runId = runId(label);
        assertEquals(3, payloadCount(TEST_COMPOSITE_THREE, "done"),
                "all rows of the composite-keyed table should be updated");
        assertTrue(runCompleted(runId), "the run should be marked complete");

        // Key order (b, a, c); chunk size 2 starts chunks at (1,x,u1) and
        // (2,x,u3), and the terminal high-water boundary captures (2,x,u3).
        List<MigrationKeyRecord> keys = boundaryKeys(runId);
        assertEquals(3, keys.size(), "two chunk boundaries plus the terminal boundary");
        assertCompositeKey(keys.get(0), 1L, "x", uuid(1));
        assertCompositeKey(keys.get(1), 2L, "x", uuid(3));
        assertCompositeKey(keys.get(2), 2L, "x", uuid(3));
    }

    /**
     * A two-column, mixed-kind (bigint, text) primary key is chunked end to
     * end: every row is processed and the boundary keys land in the right
     * arrays.
     */
    @Test
    void processesAMixedCompositeKeyedTable() {
        dsl.insertInto(TEST_COMPOSITE_MIXED, TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B)
                .values(1L, "x")
                .values(1L, "y")
                .values(2L, "x")
                .execute();

        String label = label("composite-mixed");
        run(label, 2, TEST_COMPOSITE_MIXED);

        long runId = runId(label);
        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "all rows of the mixed composite-keyed table should be updated");
        assertTrue(runCompleted(runId), "the run should be marked complete");

        // Key order (a bigint, b text); chunk size 2 starts chunks at (1,x) and
        // (2,x), and the terminal high-water boundary captures (2,x).
        List<MigrationKeyRecord> keys = boundaryKeys(runId);
        assertEquals(3, keys.size(), "two chunk boundaries plus the terminal boundary");
        assertMixedKey(keys.get(0), 1L, "x");
        assertMixedKey(keys.get(1), 2L, "x");
        assertMixedKey(keys.get(2), 2L, "x");
    }

    /**
     * Every row is processed, every chunk boundary is claimed, and the run is
     * marked complete.
     */
    @Test
    void processesAllChunksAndCompletesTheRun() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = label("all");

        run(label, 4, TEST_BIGINT);

        assertEquals(10, doneCount(TEST_BIGINT), "all rows should be updated");
        long runId = runId(label);

        // chunk starts at 1, 5, 9 and the terminal high-water boundary at 10.
        assertBoundaries(runId, new long[][]{{0, 1}, {1, 5}, {2, 9}, {3, 10}});
        assertTrue(boundaryCompleted(runId, 0), "chunk 0 should be completed");
        assertTrue(boundaryCompleted(runId, 1), "chunk 1 should be completed");
        assertTrue(boundaryCompleted(runId, 2), "chunk 2 should be completed");
        assertFalse(boundaryCompleted(runId, 3), "the terminal boundary is not a chunk and stays unclaimed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * A completed run records the total number of chunks and the number
     * completed: 10 rows at chunk size 4 give three chunks, all completed.
     */
    @Test
    void recordsTheChunkProgressCounters() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = label("counters");

        run(label, 4, TEST_BIGINT);

        long runId = runId(label);
        assertEquals(3L, totalChunks(runId), "three chunks for 10 rows at chunk size 4");
        assertEquals(3L, totalChunksCompleted(runId), "every chunk should be counted as completed");
    }

    /**
     * An empty driving table records zero total chunks and zero completed.
     */
    @Test
    void recordsZeroCountersForAnEmptyRun() {
        String label = label("counters-empty");

        run(label, 4, TEST_BIGINT);

        long runId = runId(label);
        assertEquals(0L, totalChunks(runId), "an empty run has no chunks");
        assertEquals(0L, totalChunksCompleted(runId), "an empty run has no completed chunks");
    }

    /**
     * A run that stops after a chunk failure counts only the chunks that
     * completed before it; the counter never exceeds the total.
     */
    @Test
    void countsOnlyCompletedChunksWhenAChunkFails() {
        createSource(1, 2, 3, 4);
        String label = label("counters-error");
        String template =
                "UPDATE <driving_table> SET payload ="
                        + " CASE WHEN id = 3 THEN (id / 0)::text ELSE 'done' END"
                        + " WHERE <chunking_clause>";

        assertSqlState("22012", () -> runWith(label, template, 1, 1, TEST_BIGINT));

        long runId = runId(label);
        assertEquals(4L, totalChunks(runId), "four chunks for 4 rows at chunk size 1");
        // Rows 1 and 2 complete, then the chunk for row 3 fails and the run stops.
        assertEquals(2L, totalChunksCompleted(runId),
                "only the two chunks before the failure should be counted");
    }

    /**
     * A resume keeps the counter consistent: the already-completed chunk is not
     * recounted and the remaining chunks add to it.
     */
    @Test
    void countsCompletedChunksAcrossAResume() {
        createSource(1, 2, 3, 4);
        String label = label("counters-resume");

        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);
        // Simulate the first chunk having completed, as process_migration_chunk
        // would have counted it.
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L)))
                .execute();
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED, 1L)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(2L, totalChunks(runId), "two chunks for 4 rows at chunk size 2");
        assertEquals(2L, totalChunksCompleted(runId),
                "the pre-completed chunk plus the resumed one should be counted");
    }

    /**
     * A row count that is not a multiple of the chunk size still processes every
     * row: the last (partial) chunk is bounded inclusively by the high-water
     * boundary.
     */
    @Test
    void processesAPartialLastChunk() {
        createSource(1, 2, 3, 4, 5);
        String label = label("partial");

        run(label, 2, TEST_BIGINT);

        assertEquals(5, doneCount(TEST_BIGINT), "all five rows should be updated");
        long runId = runId(label);
        // chunks start at 1, 3, 5; terminal boundary at 5.
        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 5}, {3, 5}});
        assertEquals(3, completedBoundaries(runId),
                "the three chunk boundaries should be completed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * The final chunk uses an inclusive upper bound, so with an exact multiple
     * of the chunk size the maximum row is still processed.
     */
    @Test
    void finalChunkIncludesTheMaximumRow() {
        createSource(1, 2, 3, 4);
        String label = label("exact");

        run(label, 2, TEST_BIGINT);

        assertEquals(4, doneCount(TEST_BIGINT), "the maximum row must be in the final chunk");
    }

    /**
     * An empty driving table produces a run with no boundaries, marked complete.
     */
    @Test
    void emptyDrivingTableCompletesImmediately() {
        String label = label("empty");

        run(label, 4, TEST_BIGINT);

        long runId = runId(label);
        assertTrue(runCompleted(runId), "an empty run should be complete");
        assertEquals(0, completedBoundaries(runId), "an empty run has no boundaries");
    }

    /**
     * A completed run records the actual server start and completion times, with
     * {@code completed_at} not earlier than {@code started_at}.
     */
    @Test
    void recordsRunStartAndCompletionTimestamps() {
        createSource(1, 2, 3, 4);
        String label = label("timestamps");

        run(label, 2, TEST_BIGINT);

        long runId = runId(label);
        assertTrue(runStarted(runId), "the run should record started_at");
        assertTrue(runCompleted(runId), "the run should record completed_at");

        OffsetDateTime started = dsl.select(MIGRATION_RUN.STARTED_AT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.STARTED_AT);
        OffsetDateTime boundariesCalculated = dsl.select(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT);
        OffsetDateTime completed = dsl.select(MIGRATION_RUN.COMPLETED_AT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT);
        assertNotNull(started, "the run should record started_at");
        assertNotNull(boundariesCalculated, "the run should record boundaries_calculated_at");
        assertNotNull(completed, "the run should record completed_at");
        assertFalse(boundariesCalculated.isBefore(started),
                "boundaries_calculated_at must not precede started_at");
        assertFalse(completed.isBefore(boundariesCalculated),
                "completed_at must not precede boundaries_calculated_at");
    }

    /**
     * Re-running a completed label is a no-op: the SQL is not executed again.
     */
    @Test
    void completedRunIsANoOp() {
        createSource(1, 2, 3, 4);
        String label = label("noop");

        run(label, 2, TEST_BIGINT);
        dsl.update(TEST_BIGINT).set(TEST_BIGINT.PAYLOAD, "touched").execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(4, payloadCount(TEST_BIGINT, "touched"),
                "a completed run must not revert the rows (the chunk SQL must not run again)");
        assertEquals(0, doneCount(TEST_BIGINT), "the chunk SQL must not run again on a completed run");
    }

    /**
     * A resume starts at the first unclaimed boundary; the rows of the
     * pre-completed chunk are not reprocessed.
     */
    @Test
    void resumesFromTheFirstUnclaimedBoundary() {
        createSource(1, 2, 3, 4);
        String label = label("resume");

        // Create the run and boundaries, then mark the first chunk complete by
        // hand to simulate a partially processed run.
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L)))
                .execute();
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED, 1L)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(2, doneCount(TEST_BIGINT), "only the unclaimed chunk's rows should be processed");
        assertEquals(2, completedBoundaries(runId), "both chunk boundaries should be complete");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * The chunk/final classification comes from the run's full ordered boundary
     * set, not from the unclaimed subset: with the terminal high-water boundary
     * already completed, every real chunk is still processed and none is
     * misclassified as final.
     */
    @Test
    void classifiesChunksFromTheFullBoundarySetNotTheUnclaimedSet() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("terminal-claimed");

        // Boundaries for 6 rows at chunk size 2: 0, 1, 2, and terminal 3.
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);
        // Complete the terminal boundary directly (it is not a chunk).
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(3L)))
                .execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(6, doneCount(TEST_BIGINT), "every row must be processed; no chunk may be skipped");
        assertEquals(4, completedBoundaries(runId),
                "all three chunks and the terminal boundary should be complete");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * A chunk worker error propagates with its SQLSTATE and the run is left
     * incomplete so it can be retried.
     */
    @Test
    void propagatesChunkErrors() {
        createSource(1, 2, 3, 4);
        String label = label("error");

        assertSqlState("22012", () -> Routines.runMigrationChunks(
                dsl.configuration(),
                "UPDATE <driving_table> SET payload = (1 / 0)::text WHERE <chunking_clause>",
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false));

        long runId = runId(label);
        assertFalse(runCompleted(runId), "a failed run must not be marked complete");

        // The failure is recorded in its own transaction, so the row survives
        // the re-raise that rolled the caller's transaction back.
        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(error, "the failed chunk should be recorded");
        assertEquals(0L, error.getBoundaryNo(), "the first chunk should be the failure");
        assertEquals("22012", error.getSqlstate());
        assertTrue(error.getMessage().contains("division by zero"),
                () -> "unexpected message: " + error.getMessage());
    }

    /**
     * A pre-existing active run for the label is resumed rather than recreated:
     * no 23505 is raised and every chunk is processed against that run.
     */
    @Test
    void resumesAPreExistingActiveRun() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("active");

        long existingRun = populate(TEST_BIGINT, label, TEMPLATE, 2);

        run(label, 2, TEST_BIGINT);

        assertEquals(6, doneCount(TEST_BIGINT), "every chunk of the existing run should be processed");
        assertEquals(existingRun, runId(label), "the existing run should be reused");
        assertTrue(runCompleted(existingRun), "the reused run should be marked complete");
    }

    /**
     * A resumed run ignores a differing {@code i_chunk_size}: the boundaries (and
     * their chunk size) are fixed when the run was created.
     */
    @Test
    void resumeIgnoresADifferingChunkSize() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("chunk-size-resume");

        // Stored chunk size 2 gives boundaries 0,1,2 and terminal 3.
        long existingRun = populate(TEST_BIGINT, label, TEMPLATE, 2);

        run(label, 10, TEST_BIGINT);

        assertEquals(3, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(existingRun)
                                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.lt(3L))),
                "the stored boundaries must be used, not recomputed for chunk size 10");
        assertEquals(6, doneCount(TEST_BIGINT), "every row of the stored chunks should be processed");
        assertTrue(runCompleted(existingRun), "the reused run should be marked complete");
    }

    /**
     * A resumed run uses the driving table recorded when the run was created: a
     * different input table is ignored, so the stored boundaries and the
     * rendered chunk SQL always refer to the same table.
     */
    @Test
    void resumeUsesTheStoredDrivingTable() {
        createSource(1, 2, 3, 4);
        createSource(TEST_OTHER, 1, 2, 3, 4);
        String label = label("stored-table");
        populate(TEST_BIGINT, label, TEMPLATE, 2);

        // A resumed call naming a different driving table must be ignored.
        run(label, 2, TEST_OTHER);

        assertEquals(4, doneCount(TEST_BIGINT), "the stored driving table should be processed");
        assertEquals(0, payloadCount(TEST_OTHER, "done"),
                "the input table must not be touched");
    }

    /**
     * On resume the stored {@code sql_text} is used and a differing input is
     * ignored; a resumed run whose stored SQL was adjusted via
     * {@code set_migration_run_sql_text} then uses the adjusted SQL.
     */
    @Test
    void resumeUsesTheStoredSqlText() {
        createSource(1, 2, 3, 4);
        String label = label("stored");

        // An unfinished run whose stored SQL writes 'first'.
        populate(TEST_BIGINT, label,
                "UPDATE <driving_table> SET payload = 'first' WHERE <chunking_clause>", 2);

        // A resumed call with a different input must ignore the input.
        runWith(label, "UPDATE <driving_table> SET payload = 'ignored' WHERE <chunking_clause>",
                2, 1, TEST_BIGINT);

        assertEquals(4, payloadCount(TEST_BIGINT, "first"), "the stored SQL should be used on resume");
        assertEquals(0, payloadCount(TEST_BIGINT, "ignored"),
                "the caller's differing SQL should be ignored");
    }

    /**
     * A resumed run whose stored SQL was changed via
     * {@code set_migration_run_sql_text} uses the new SQL.
     */
    @Test
    void setMigrationRunSqlTextChangesTheResumedSql() {
        createSource(1, 2, 3, 4);
        String label = label("set-sql");

        // Create an unfinished run (populate only), then adjust its SQL.
        populate(TEST_BIGINT, label,
                "UPDATE <driving_table> SET payload = 'first' WHERE <chunking_clause>", 2);
        Routines.setMigrationRunSqlText(dsl.configuration(), label,
                "UPDATE <driving_table> SET payload = 'second' WHERE <chunking_clause>");

        runWith(label, "UPDATE <driving_table> SET payload = 'ignored' WHERE <chunking_clause>",
                2, 1, TEST_BIGINT);

        assertEquals(4, payloadCount(TEST_BIGINT, "second"), "the adjusted (stored) SQL should be used");
        assertEquals(0, payloadCount(TEST_BIGINT, "first"), "the original stored SQL should not be used");
    }

    /**
     * {@code set_migration_run_sql_text} raises {@code P0002} when there is no
     * unfinished run for the label.
     */
    @Test
    void setMigrationRunSqlTextRaisesWhenNoUnfinishedRun() {
        assertSqlState("P0002", () -> Routines.setMigrationRunSqlText(
                dsl.configuration(), "no-such-label", TEMPLATE));
    }

    /**
     * {@code set_migration_run_sql_text} rejects a value that is not a valid
     * chunking template with {@code 22023}.
     */
    @Test
    void setMigrationRunSqlTextRejectsAnInvalidTemplate() {
        createSource(1, 2, 3, 4);
        String label = label("invalid-template");
        populate(TEST_BIGINT, label, TEMPLATE, 2);

        assertSqlState("22023", () -> Routines.setMigrationRunSqlText(
                dsl.configuration(), label, "UPDATE <driving_table> SET x = 1"));
    }

    /**
     * A null SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSqlText() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), null, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false));
    }

    /**
     * A blank label is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankLabel() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "   ", 2, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false));
    }

    /**
     * A non-positive chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNonPositiveChunkSize() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 0, 1, "t", ChunkingStrategy.primary_key, 1, 0, null, false));
    }

    /**
     * A null alias is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullAlias() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2, 1, null, ChunkingStrategy.primary_key, 1, 0, null, false));
    }

    /**
     * The primary-key column is resolved from the catalog, not assumed to be
     * named {@code id}: a table whose key is {@code key} still chunks correctly.
     */
    @Test
    void resolvesThePrimaryKeyColumnNameFromTheCatalog() {
        for (long id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_KEY, TEST_KEY.KEY).values(id).execute();
        }
        String label = label("catalog-pk");

        run(label, 2, TEST_KEY);

        assertEquals(6, doneCount(TEST_KEY), "all rows should be processed using the catalog key");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * An {@code integer} (non-bigint) primary key is supported end to end.
     */
    @Test
    void processesATableWithAnIntegerPrimaryKey() {
        for (int id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_INTEGER, TEST_INTEGER.ID).values(id).execute();
        }
        String label = label("integer-pk");

        run(label, 2, TEST_INTEGER);

        assertEquals(6, doneCount(TEST_INTEGER), "every row of an integer-keyed table should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A {@code smallint} (non-bigint) primary key is supported end to end.
     */
    @Test
    void processesATableWithASmallintPrimaryKey() {
        for (int id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_SMALLINT, TEST_SMALLINT.ID).values((short) id).execute();
        }
        String label = label("smallint-pk");

        run(label, 2, TEST_SMALLINT);

        assertEquals(6, doneCount(TEST_SMALLINT), "every row of a smallint-keyed table should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A table with a text primary key is chunked end to end, including a key
     * that contains a quote (the rendered literal must escape it).
     */
    @Test
    void processesATableWithATextPrimaryKey() {
        for (String id : new String[]{"a", "b", "o'brien", "z"}) {
            dsl.insertInto(TEST_TEXT, TEST_TEXT.ID).values(id).execute();
        }
        String label = label("text-pk");

        run(label, 2, TEST_TEXT);

        long runId = runId(label);
        assertEquals(4, doneCount(TEST_TEXT), "every row of a text-keyed table should be processed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
        assertEquals(List.of("a", "o'brien", "z"), textBoundaryValues(runId),
                "the stored boundaries should hold the chunk start keys and the high-water key");
    }

    /**
     * A table with a uuid primary key is chunked end to end, even though
     * PostgreSQL has no {@code min}/{@code max} aggregate for uuid.
     */
    @Test
    void processesATableWithAUuidPrimaryKey() {
        for (int i = 1; i <= 6; i++) {
            dsl.insertInto(TEST_UUID, TEST_UUID.ID).values(uuid(i)).execute();
        }
        String label = label("uuid-pk");

        run(label, 2, TEST_UUID);

        long runId = runId(label);
        assertEquals(6, doneCount(TEST_UUID), "every row of a uuid-keyed table should be processed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
        assertEquals(
                List.of(uuid(1), uuid(3), uuid(5), uuid(6)),
                uuidBoundaryValues(runId),
                "the stored boundaries should hold the chunk start keys and the high-water key");
    }

    /**
     * With several threads the run still processes every chunk and completes.
     */
    @Test
    void processesAllChunksWithSeveralThreads() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = label("threads");

        runWith(label, TEMPLATE, 4, 3, TEST_BIGINT);

        long runId = runId(label);
        assertEquals(10, doneCount(TEST_BIGINT), "all rows should be processed");
        assertBoundaries(runId, new long[][]{{0, 1}, {1, 5}, {2, 9}, {3, 10}});
        assertEquals(3, completedBoundaries(runId), "the three chunk boundaries should be completed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * When one chunk fails with several threads in flight, the failure is
     * recorded, the chunks that did not fail still commit, and the run is left
     * incomplete.
     */
    @Test
    void aFailedChunkLeavesOtherThreadsCommittedAndTheRunIncomplete() {
        createSource(1, 2, 3, 4);
        String label = label("threads-error");
        String template =
                "UPDATE <driving_table> SET payload ="
                        + " CASE WHEN id = 3 THEN (id / 0)::text ELSE 'done' END"
                        + " WHERE <chunking_clause>";

        assertSqlState("22012", () -> runWith(label, template, 1, 4, TEST_BIGINT));

        long runId = runId(label);
        assertFalse(runCompleted(runId), "a failed run must not be marked complete");
        // With one row per chunk, rows 1, 2 and 4 succeed and row 3 fails; the
        // count of committed chunks is deterministic because the failure is
        // observed before that worker's chunk commits. Assert on the failing
        // chunk rather than an exact in-flight count.
        assertTrue(doneCount(TEST_BIGINT) >= 1 && doneCount(TEST_BIGINT) <= 3,
                "only the chunks that did not fail should have committed");
        assertEquals(0L, dsl.fetchCount(TEST_BIGINT, TEST_BIGINT.ID.eq(3L)
                        .and(TEST_BIGINT.PAYLOAD.isNotNull())),
                "the failing chunk's row (id 3) must not be updated");
        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .and(MIGRATION_ERROR.BOUNDARY_NO.eq(2L))
                .fetchOne();
        assertNotNull(error, "the failing chunk (boundary 2, row id 3) should be recorded");
        assertEquals("22012", error.getSqlstate());
        assertEquals(2L, error.getBoundaryNo(), "the failing boundary is the one for id 3");
    }

    /**
     * A P0002 raised by the chunk SQL itself must not be mistaken for the
     * coordinator's benign claim-protocol signal. The worker failure is recorded
     * and stops the run instead of being retried forever.
     */
    @Test
    void chunkSqlNoDataFoundIsNotTreatedAsABenignClaim() {
        createSource(1, 2, 3);
        String label = label("chunk-p0002");
        String template = "DO $$ DECLARE l_value integer; BEGIN"
                + " SELECT 1 INTO STRICT l_value FROM <driving_table>"
                + " WHERE <chunking_clause> AND false; END $$";

        assertSqlState("P0002", () -> runWith(label, template, 2, 1, TEST_BIGINT));

        long runId = runId(label);
        assertFalse(runCompleted(runId), "the failed run must not be marked complete");
        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(error, "the P0002 must be recorded as a chunk error");
        assertEquals("P0002", error.getSqlstate());
        assertEquals(0, completedBoundaries(runId),
                "the boundary whose SQL raised P0002 must remain unclaimed");
    }

    /**
     * A P0C01 raised by the chunk SQL is only benign when the boundary is
     * actually completed or cancelled. A still-pending boundary must be recorded
     * as a failure rather than retried forever.
     */
    @Test
    void chunkSqlClaimCodeWithPendingBoundaryIsNotBenign() {
        createSource(1, 2, 3);
        String label = label("chunk-p0c01");
        String template = "DO $$ BEGIN"
                + " PERFORM 1 FROM <driving_table> WHERE <chunking_clause>;"
                + " RAISE EXCEPTION 'spoofed claim' USING ERRCODE = 'P0C01';"
                + " END $$";

        assertSqlState("P0C01", () -> runWith(label, template, 2, 1, TEST_BIGINT));

        long runId = runId(label);
        assertFalse(runCompleted(runId), "the failed run must not be marked complete");
        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(error, "the spoofed claim code must be recorded as a chunk error");
        assertEquals("P0C01", error.getSqlstate());
    }

    /**
     * Requesting more threads than the server allows is rejected.
     */
    @Test
    void rejectsTooManyThreads() {
        int tooMany = dsl.fetchOne(
                        "SELECT pg_catalog.current_setting('max_worker_processes')::int")
                .get(0, Integer.class) + 1;

        assertSqlState("22023", () -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2,
                tooMany, "t", ChunkingStrategy.primary_key, 1, 0, null, false));
    }

    /**
     * A resumed run uses the threads recorded when it was created: a differing
     * input is ignored.
     */
    @Test
    void resumeUsesTheStoredThreads() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("threads-resume");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2, 2);

        runWith(label, TEMPLATE, 2, 5, TEST_BIGINT);

        int stored = dsl.select(MIGRATION_RUN.THREADS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.THREADS);
        assertEquals(2, stored, "the stored threads should be used, not the input 5");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * {@code set_migration_run_threads} changes the recorded threads of an
     * unfinished run, and the next call uses the adjusted value.
     */
    @Test
    void setMigrationRunThreadsChangesTheStoredThreads() {
        createSource(1, 2, 3, 4);
        String label = label("set-threads");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);

        Routines.setMigrationRunThreads(dsl.configuration(), label, 3);

        int stored = dsl.select(MIGRATION_RUN.THREADS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.THREADS);
        assertEquals(3, stored, "the adjusted threads should be stored");

        run(label, 2, TEST_BIGINT);
        assertEquals(4, doneCount(TEST_BIGINT), "the run should process every row");
    }

    /**
     * A new run records the requested per-chunk sleep.
     */
    @Test
    void storesTheRequestedSleepOnANewRun() {
        createSource(1, 2, 3);
        String label = label("sleep-store");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2, 1, 250);

        int stored = dsl.select(MIGRATION_RUN.SLEEP_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.SLEEP_MS);
        assertEquals(250, stored, "the requested sleep should be stored on the run");
    }

    /**
     * A resumed run uses the sleep recorded when it was created, not the input.
     */
    @Test
    void resumeUsesTheStoredSleep() {
        createSource(1, 2, 3);
        String label = label("sleep-resume");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 1, 1, 50);

        long started = System.nanoTime();
        runWithSleep(label, TEMPLATE, 1, 1, 0, TEST_BIGINT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        int stored = dsl.select(MIGRATION_RUN.SLEEP_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.SLEEP_MS);
        assertEquals(50, stored, "the stored sleep should be preserved");
        assertTrue(elapsedMs >= 70,
                "the stored 50ms sleep should be applied even though the input was 0; took "
                        + elapsedMs);
        assertTrue(runCompleted(runId), "the run should complete");
    }

    /**
     * {@code set_migration_run_sleep_ms} changes an unfinished run for its next
     * call.
     */
    @Test
    void setMigrationRunSleepChangesTheStoredValue() {
        createSource(1, 2, 3);
        String label = label("set-sleep");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 1);

        Routines.setMigrationRunSleepMs(dsl.configuration(), label, 50);

        int stored = dsl.select(MIGRATION_RUN.SLEEP_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.SLEEP_MS);
        assertEquals(50, stored, "the adjusted sleep should be stored");

        long started = System.nanoTime();
        runWithSleep(label, TEMPLATE, 1, 1, 0, TEST_BIGINT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs >= 70,
                "the adjusted 50ms sleep should be applied; took " + elapsedMs);
        assertTrue(runCompleted(runId), "the run should process every row");
    }

    /**
     * {@code set_migration_run_sleep_ms} raises {@code P0002} when there is no
     * unfinished run for the label.
     */
    @Test
    void setMigrationRunSleepRaisesWhenNoUnfinishedRun() {
        assertSqlState("P0002", () -> Routines.setMigrationRunSleepMs(
                dsl.configuration(), "no-such-label", 10));
    }

    /**
     * A negative sleep is rejected by the setter.
     */
    @Test
    void setMigrationRunSleepRejectsNegativeValues() {
        assertDomainViolation(() -> Routines.setMigrationRunSleepMs(
                dsl.configuration(), "any-label", -1));
    }

    /**
     * A NULL sleep is rejected by the setter rather than leaking to the column's
     * not-null constraint.
     */
    @Test
    void setMigrationRunSleepRejectsNull() {
        assertDomainViolation(() -> Routines.setMigrationRunSleepMs(
                dsl.configuration(), "any-label", null));
    }

    /**
     * A NULL initial sleep is rejected before a run is created.
     */
    @Test
    void runMigrationChunksRejectsNullSleep() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT),
                "l", 2, 1, "t", ChunkingStrategy.primary_key, 1, null, null, false));
    }

    /**
     * A non-zero sleep delays the run between successful chunks.
     */
    @Test
    void sleepsBetweenChunks() {
        createSource(1, 2, 3);
        String label = label("sleep-delay");

        long started = System.nanoTime();
        runWithSleep(label, TEMPLATE, 1, 1, 100, TEST_BIGINT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs >= 180,
                "two inter-chunk sleeps of 100ms should take at least 180ms, took "
                        + elapsedMs);
        assertTrue(runCompleted(runId(label)), "the throttled run should complete");
    }

    /**
     * The throttle does not delay the run after its final chunk.
     */
    @Test
    void skipsSleepAfterTheFinalChunk() {
        createSource(1);
        String label = label("sleep-final");

        long started = System.nanoTime();
        runWithSleep(label, TEMPLATE, 1, 1, 2_000, TEST_BIGINT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < 1_500,
                "a single-chunk run should not wait for the 2s final sleep; took "
                        + elapsedMs);
        assertTrue(runCompleted(runId(label)), "the run should complete");
    }

    /**
     * {@code set_migration_run_threads} raises {@code P0002} when there is no
     * unfinished run for the label.
     */
    @Test
    void setMigrationRunThreadsRaisesWhenNoUnfinishedRun() {
        assertSqlState("P0002", () -> Routines.setMigrationRunThreads(
                dsl.configuration(), "no-such-label", 2));
    }

    /**
     * A cancelled run is terminal, so the thread, sleep and SQL mutators report
     * {@code P0002} instead of silently writing to a run that can never continue.
     */
    @Test
    void cancelledRunRejectsMetadataAdjustments() {
        createSource(1, 2, 3, 4);
        String label = label("cancelled-adjust");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        assertSqlState("P0002", () -> Routines.setMigrationRunThreads(
                dsl.configuration(), label, 3));
        assertSqlState("P0002", () -> Routines.setMigrationRunSleepMs(
                dsl.configuration(), label, 5));
        assertSqlState("P0002", () -> Routines.setMigrationRunSqlText(
                dsl.configuration(), label, TEMPLATE));
    }

    /**
     * A non-positive thread count is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNonPositiveThreads() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2, 0,
                "t", ChunkingStrategy.primary_key, 1, 0, null, false));
    }

    /**
     * A non-positive poll timeout is rejected by the positive-integer domain:
     * {@code pg_background_wait_any} treats a non-positive timeout as
     * wait-forever, which the coordinator must not use.
     */
    @Test
    void rejectsNonPositivePollTimeout() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2, 1,
                "t", ChunkingStrategy.primary_key, 0, 0, null, false));
    }

    /**
     * A larger poll timeout (fewer coordinator wake-ups) still processes every
     * chunk and completes the run.
     */
    @Test
    void processesEveryChunkWithALargerPollTimeout() {
        createSource(1, 2, 3, 4);
        String label = label("poll-timeout");

        Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, 1,
                "t", ChunkingStrategy.primary_key, 50, 0, null, false);

        assertEquals(4, doneCount(TEST_BIGINT), "every row should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A failure stops the coordinator from launching further chunks: with more
     * chunks than threads, at least one chunk after the failure is left
     * unprocessed.
     */
    @Test
    void aFailedChunkStopsLaunchingFurtherChunks() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("threads-stop");
        String template =
                "UPDATE <driving_table> SET payload ="
                        + " CASE WHEN id = 3 THEN (id / 0)::text ELSE 'done' END"
                        + " WHERE <chunking_clause>";

        assertSqlState("22012", () -> runWith(label, template, 1, 2, TEST_BIGINT));

        long runId = runId(label);
        assertFalse(runCompleted(runId), "a failed run must not be marked complete");
        // Chunk size 1 over 6 rows gives 6 chunks; without suppression the other
        // 5 would all be processed, so a count below 5 proves one was suppressed.
        assertTrue(doneCount(TEST_BIGINT) < 5,
                "a chunk after the failure should have been suppressed");
        assertNotNull(dsl.selectFrom(MIGRATION_ERROR)
                        .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                        .fetchOne(),
                "the failed chunk should be recorded");
    }

    /**
     * On a resumed run the stored thread count governs scheduling: an input that
     * exceeds {@code max_worker_processes} is ignored, so the call succeeds
     * instead of rejecting or over-launching.
     */
    @Test
    void storedThreadsGovernSchedulingOnResume() {
        for (long id = 1; id <= 20; id++) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
        String label = label("stored-threads-govern");
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 1, 1);

        int tooMany = dsl.fetchOne(
                        "SELECT pg_catalog.current_setting('max_worker_processes')::int")
                .get(0, Integer.class) + 1;

        runWith(label, TEMPLATE, 1, tooMany, TEST_BIGINT);

        assertEquals(20, doneCount(TEST_BIGINT), "the run should process every row");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * The alias argument defaults to {@code t} at the SQL level when omitted.
     */
    @Test
    void usesTheDefaultAliasWhenOmitted() {
        createSource(1, 2, 3, 4);
        String label = label("default-alias");

        RunMigrationChunks routine = runRoutine(label);
        routine.setIChunkSize(2);
        routine.execute(dsl.configuration());

        assertEquals(4, doneCount(TEST_BIGINT), "the default alias t should be used");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * The chunk-size argument defaults to 1000 at the SQL level when omitted.
     */
    @Test
    void usesTheDefaultChunkSizeWhenOmitted() {
        createSource(1, 2, 3, 4);
        String label = label("default-chunk-size");

        runRoutine(label).execute(dsl.configuration());

        assertEquals(4, doneCount(TEST_BIGINT), "every row should be processed with the default chunk size");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * Builds a {@code run_migration_chunks} call with the required arguments
     * set, leaving the defaulted ones for jOOQ to omit.
     */
    private RunMigrationChunks runRoutine(String label) {
        RunMigrationChunks routine = new RunMigrationChunks();
        routine.setISqlText(TEMPLATE);
        routine.setIDrivingTableSchemaName(schema(TEST_BIGINT));
        routine.setIDrivingTableName(name(TEST_BIGINT));
        routine.setILabel(label);
        return routine;
    }

    /**
     * Returns the run's boundary keys in order as their text attribute.
     */
    private List<String> textBoundaryValues(long runId) {
        return dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .stream()
                .map(key -> key.getTextValues()[0])
                .toList();
    }

    /**
     * Returns the run's boundary keys in order as their uuid attribute.
     */
    private List<UUID> uuidBoundaryValues(long runId) {
        return dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .stream()
                .map(key -> key.getUuidValues()[0])
                .toList();
    }

    private void createSource(long... ids) {
        seedBigint(ids);
    }

    private void createSource(Table<?> table, long... ids) {
        for (long id : ids) {
            dsl.insertInto(table, field("id", Long.class)).values(id).execute();
        }
    }

    private long populate(Table<?> table, String label, String template, int chunkSize) {
        return populate(table, label, template, chunkSize, 1);
    }

    private long populate(Table<?> table, String label, String template, int chunkSize, int threads) {
        return populate(table, label, template, chunkSize, threads, 0);
    }

    private long populate(Table<?> table, String label, String template, int chunkSize, int threads,
                          int sleepMs) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(table), name(table), label, template, chunkSize, threads, ChunkingStrategy.primary_key, "t", sleepMs, null, false);
    }

    private String label(String suffix) {
        return uniqueLabel("run-chunks-" + suffix);
    }

    private void run(String label, int chunkSize, Table<?> table) {
        runWith(label, TEMPLATE, chunkSize, 1, table);
    }

    private void runWith(String label, String template, int chunkSize, int threads, Table<?> table) {
        runWithSleep(label, template, chunkSize, threads, 0, table);
    }

    private void runWithSleep(String label, String template, int chunkSize, int threads, int sleepMs,
                              Table<?> table) {
        Routines.runMigrationChunks(
                dsl.configuration(), template, schema(table), name(table), label, chunkSize, threads,
                "t", ChunkingStrategy.primary_key, 1, sleepMs, null, false);
    }

    private void runOrderedKey(String label, int chunkSize, int threads) {
        Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE,
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(),
                label, chunkSize, threads, "t", ChunkingStrategy.ordered_key, 1, 0,
                "test_ordered_key_created_at_id_idx", false);
    }

    /**
     * Returns the run's boundary keys in boundary order.
     */
    private List<MigrationKeyRecord> boundaryKeys(long runId) {
        return dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_ID);
    }

    private void assertBoundaries(long runId, long[][] expected) {
        List<MigrationBoundaryRecord> actual = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
        assertEquals(expected.length, actual.size(), "boundary count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual.get(i).getBoundaryNo().longValue(),
                    "boundary_no " + i);
            assertEquals(expected[i][1], actual.get(i).getBoundaryId().getBigintValues()[0].longValue(),
                    "boundary_id " + i);
        }
    }

    private int doneCount(Table<?> table) {
        return payloadCount(table, "done");
    }

}
