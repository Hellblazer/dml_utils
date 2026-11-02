package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeId1.TEST_COMPOSITE_ID1;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeThree.TEST_COMPOSITE_THREE;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestInteger.TEST_INTEGER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestKey.TEST_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitioned.TEST_PARTITIONED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.populate_migration_boundaries}: it records a
 * migration run and one fixed-row chunk boundary per chunk, ending with a
 * terminal high-water boundary at the captured maximum primary key.
 */
class PopulateMigrationBoundariesTest extends PostgresTestBase {

    private static final String LABEL = "boundary-test-run";
    private static final String SQL_TEXT = "SELECT 1";

    /**
     * Each test gets a distinct label so the one-active-run-per-label rule does
     * not couple tests that populate in the same database.
     */
    private int labelCounter;

    /**
     * Clears the fixture tables this class populates boundaries for.
     */
    @BeforeEach
    void resetFixtures() {
        for (Table<?> table : List.of(TEST_BIGINT, TEST_TEXT, TEST_UUID, TEST_INTEGER, TEST_KEY,
                TEST_COMPOSITE_PK, TEST_COMPOSITE_ID1, TEST_COMPOSITE_THREE, TEST_ORDERED_KEY)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * An empty source table still creates a run, but no boundaries.
     */
    @Test
    void emptySourceCreatesRunWithNoBoundaries() {
        long runId = populate(10);

        assertTrue(runId > 0, "a run id should be returned");
        assertEquals(1, runCount(runId), "the run row should exist");
        assertTrue(boundaries(runId).isEmpty(), "an empty source has no boundaries");
    }

    /**
     * An ordered_key run resolves the named index, stores the descriptor with the
     * run and chunks in index order.
     */
    @Test
    void populatesOrderedKeyBoundariesAndStoresTheDescriptor() {
        seedOrderedKey();

        long runId = populateOrderedKey();

        MigrationRunRecord run = run(runId);
        assertEquals(ChunkingStrategy.ordered_key, run.getChunkBy());
        assertEquals("test_ordered_key_created_at_id_idx", run.getOrderedKeyIndex());
        assertArrayEquals(new String[]{"created_at", "id"}, run.getOrderedKeyColumns());
        assertArrayEquals(new String[]{"timestamptz", "bigint"}, run.getOrderedKeyKinds());

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(4, boundaries.size(), "three chunk starts plus the terminal");
        assertTimestamptzBigint(boundaries.get(0), 1, 1L);
        assertTimestamptzBigint(boundaries.get(1), 2, 3L);
        assertTimestamptzBigint(boundaries.get(2), 3, 5L);
        assertTimestamptzBigint(boundaries.get(3), 3, 5L);
        assertEquals(3L, run(runId).getTotalChunks().longValue(), "three chunks");
    }

    /**
     * An ordered_key run without an index, or a non-ordered run with one, is
     * rejected with {@code 22023} before a run is created.
     */
    @Test
    void rejectsAnInvalidStrategyIndexCombination() {
        int before = dsl.fetchCount(MIGRATION_RUN);

        assertSqlState("22023", () -> populate(TEST_ORDERED_KEY, ChunkingStrategy.ordered_key, null));
        assertSqlState("22023", () -> populate(TEST_BIGINT, ChunkingStrategy.primary_key,
                "test_bigint_pkey"));

        assertEquals(before, dsl.fetchCount(MIGRATION_RUN), "no run is created");
    }

    /**
     * Every unsafe ordered key is rejected before a run or boundary is written:
     * non-unique, non-btree, partial, expression, DESC, NULLS FIRST, nullable,
     * non-default opclass and collation-mismatched indexes with {@code 0A000};
     * over-long and unsupported-type keys with {@code 22023}; and a partitioned
     * parent with {@code 0A000}.
     */
    @Test
    void rejectsEveryUnsafeOrderedKeyBeforeWritingAnything() {
        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        int boundariesBefore = dsl.fetchCount(MIGRATION_BOUNDARY);

        for (String index : List.of(
                "test_ordered_key_nonunique_idx",
                "test_ordered_key_hash_idx",
                "test_ordered_key_partial_idx",
                "test_ordered_key_expression_idx",
                "test_ordered_key_desc_idx",
                "test_ordered_key_nulls_first_idx",
                "test_ordered_key_nullable_idx",
                "test_ordered_key_opclass_idx",
                "test_ordered_key_collation_idx")) {
            assertSqlState("0A000", () -> populate(TEST_ORDERED_KEY,
                    ChunkingStrategy.ordered_key, index));
        }
        for (String index : List.of(
                "test_ordered_key_four_idx",
                "test_ordered_key_numeric_idx")) {
            assertSqlState("22023", () -> populate(TEST_ORDERED_KEY,
                    ChunkingStrategy.ordered_key, index));
        }
        assertSqlState("0A000", () -> populate(TEST_PARTITIONED,
                ChunkingStrategy.ordered_key, "test_partitioned_id_idx"));

        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN), "no run is written");
        assertEquals(boundariesBefore, dsl.fetchCount(MIGRATION_BOUNDARY),
                "no boundary is written");
    }

    /**
     * A full table produces one boundary per chunk start plus the terminal
     * boundary at the maximum key.
     */
    @Test
    void createsAStartingBoundaryPerChunkAndATerminalBoundary() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        long runId = populate(4);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 5}, {2, 9}, {3, 10}});
    }

    /**
     * Chunks are cut by row number, not by key range, so gaps in the key
     * sequence do not change the boundary count.
     */
    @Test
    void chunksAreCutByRowNumberNotKeyRange() {
        createSource(1, 2, 5, 8, 10, 11, 15, 20, 24, 30);

        long runId = populate(4);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 10}, {2, 24}, {3, 30}});
    }

    /**
     * A single-row source produces one chunk plus the terminal boundary, both
     * at that row's key.
     */
    @Test
    void singleRowProducesChunkAndTerminalBoundary() {
        createSource(42);

        long runId = populate(10);

        assertBoundaries(runId, new long[][]{{0, 42}, {1, 42}});
    }

    /**
     * A chunk size of one makes every row a chunk start, with the terminal
     * boundary repeating the maximum.
     */
    @Test
    void chunkSizeOneStartsEveryRow() {
        createSource(1, 2, 3);

        long runId = populate(1);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 2}, {2, 3}, {3, 3}});
    }

    /**
     * A chunk size larger than the source produces a single chunk covering all
     * rows plus the terminal boundary.
     */
    @Test
    void chunkLargerThanSourceProducesOneChunk() {
        createSource(1, 2, 3);

        long runId = populate(10);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}});
    }

    /**
     * When the row count is an exact multiple of the chunk size the last chunk
     * still gets a starting boundary, ending on the maximum key.
     */
    @Test
    void exactMultipleOfChunkSizeEndsOnTheMaximum() {
        createSource(1, 2, 3, 4);

        long runId = populate(2);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 4}});
    }

    /**
     * Boundaries are captured at population time, so keys inserted afterwards
     * do not change an existing run's boundaries or extend its terminal range.
     */
    @Test
    void laterInsertsDoNotChangeCapturedBoundaries() {
        createSource(1, 2, 3, 4, 5);

        long runId = populate(2);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 5}, {3, 5}});

        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(100L).execute();

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 5}, {3, 5}});
    }

    /**
     * Two runs over the same source are independent and keep their own
     * boundaries.
     */
    @Test
    void multipleRunsAreIndependent() {
        createSource(1, 2, 3, 4);

        long firstRun = populate(2);
        long secondRun = populate(LABEL + "-" + ++labelCounter, 3);

        assertNotEquals(firstRun, secondRun, "each call creates a new run");
        assertBoundaries(firstRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
        assertBoundaries(secondRun, new long[][]{{0, 1}, {1, 4}, {2, 4}});
    }

    /**
     * A text primary key is chunked in key order: one starting boundary per
     * chunk plus the terminal high-water boundary, all in the text attribute.
     */
    @Test
    void createsBoundariesForATextPrimaryKey() {
        for (int i = 1; i <= 10; i++) {
            dsl.insertInto(TEST_TEXT, TEST_TEXT.ID).values("k" + String.format("%02d", i)).execute();
        }

        long runId = populate(TEST_TEXT, 4);

        List<MigrationBoundaryRecord> actual = boundaries(runId);
        assertEquals(4, actual.size(), "ten rows at chunk size four yield four boundaries");
        assertEquals("k01", actual.get(0).getBoundaryId().getTextValues()[0]);
        assertEquals("k05", actual.get(1).getBoundaryId().getTextValues()[0]);
        assertEquals("k09", actual.get(2).getBoundaryId().getTextValues()[0]);
        assertEquals("k10", actual.get(3).getBoundaryId().getTextValues()[0]);
    }

    /**
     * A uuid primary key is chunked in key order, even though PostgreSQL has no
     * {@code min}/{@code max} aggregate for uuid; the terminal boundary holds the
     * captured maximum uuid.
     */
    @Test
    void createsBoundariesForAUuidPrimaryKey() {
        for (int i = 1; i <= 10; i++) {
            dsl.insertInto(TEST_UUID, TEST_UUID.ID).values(uuid(i)).execute();
        }

        long runId = populate(TEST_UUID, 4);

        List<MigrationBoundaryRecord> actual = boundaries(runId);
        assertEquals(4, actual.size(), "ten rows at chunk size four yield four boundaries");
        assertEquals(uuid(1), actual.get(0).getBoundaryId().getUuidValues()[0]);
        assertEquals(uuid(5), actual.get(1).getBoundaryId().getUuidValues()[0]);
        assertEquals(uuid(9), actual.get(2).getBoundaryId().getUuidValues()[0]);
        assertEquals(uuid(10), actual.get(3).getBoundaryId().getUuidValues()[0]);
    }

    /**
     * An {@code integer} (non-bigint) primary key is packed into the bigint
     * attribute.
     */
    @Test
    void createsBoundariesForAnIntegerPrimaryKey() {
        for (int i = 1; i <= 10; i++) {
            dsl.insertInto(TEST_INTEGER, TEST_INTEGER.ID).values(i).execute();
        }

        long runId = populate(TEST_INTEGER, 4);

        List<MigrationBoundaryRecord> actual = boundaries(runId);
        assertEquals(4, actual.size(), "ten rows at chunk size four yield four boundaries");
        assertEquals(1L, actual.get(0).getBoundaryId().getBigintValues()[0]);
        assertEquals(5L, actual.get(1).getBoundaryId().getBigintValues()[0]);
        assertEquals(9L, actual.get(2).getBoundaryId().getBigintValues()[0]);
        assertEquals(10L, actual.get(3).getBoundaryId().getBigintValues()[0]);
    }

    /**
     * A mixed-kind composite primary key (integer, text, uuid) packs each value
     * into the array for its kind, position-aligned: the matching index holds
     * the value and the other positions are NULL, with no compaction of the
     * NULL holes.
     */
    @Test
    void packsAMixedKindCompositeKeyPositionAligned() {
        UUID c1 = uuid(1);
        dsl.insertInto(TEST_COMPOSITE_THREE, TEST_COMPOSITE_THREE.B, TEST_COMPOSITE_THREE.A,
                        TEST_COMPOSITE_THREE.C)
                .values(7, "x", c1)
                .execute();

        long runId = populate(TEST_COMPOSITE_THREE, 4);

        List<MigrationBoundaryRecord> actual = boundaries(runId);
        MigrationBoundaryRecord first = actual.get(0);
        assertArrayEquals(new Long[]{7L, null, null}, first.getBoundaryId().getBigintValues(),
                "the integer (first key part) lands at array index 0; the other positions stay NULL");
        assertArrayEquals(new String[]{null, "x", null}, first.getBoundaryId().getTextValues(),
                "the text (second key part) lands at array index 1");
        assertArrayEquals(new UUID[]{null, null, c1}, first.getBoundaryId().getUuidValues(),
                "the uuid (third key part) lands at array index 2");
    }

    /**
     * A duplicate-kind composite key (two bigints) packs both values into the
     * single bigint array, leaving the unused text and uuid arrays NULL.
     */
    @Test
    void packsADuplicateKindCompositeKeyIntoOneArray() {
        dsl.insertInto(TEST_COMPOSITE_PK, TEST_COMPOSITE_PK.A, TEST_COMPOSITE_PK.B)
                .values(1L, 2L)
                .execute();

        long runId = populate(TEST_COMPOSITE_PK, 4);

        MigrationBoundaryRecord first = boundaries(runId).get(0);
        assertArrayEquals(new Long[]{1L, 2L}, first.getBoundaryId().getBigintValues(),
                "both bigint key parts share the bigint array, in key order");
        assertNull(first.getBoundaryId().getTextValues(), "the unused text array stays NULL");
        assertNull(first.getBoundaryId().getUuidValues(), "the unused uuid array stays NULL");
    }

    /**
     * A key column literally named {@code id1} at a non-matching position is
     * ordered by its own value, not by the output alias {@code id1} (which is
     * the first key column). The terminal boundary must capture the true
     * maximum tuple, so this catches the alias/column ORDER BY collision.
     */
    @Test
    void ordersByKeyColumnsWhenAKeyColumnIsNamedId1() {
        dsl.insertInto(TEST_COMPOSITE_ID1, TEST_COMPOSITE_ID1.X, TEST_COMPOSITE_ID1.ID1)
                .values(1L, 5L)
                .values(2L, 1L)
                .execute();

        long runId = populate(TEST_COMPOSITE_ID1, 10);

        List<MigrationBoundaryRecord> actual = boundaries(runId);
        // One chunk start at (1,5) plus the terminal high-water boundary (2,1).
        assertEquals(2, actual.size(), "one chunk boundary plus the terminal boundary");
        assertArrayEquals(new Long[]{1L, 5L}, actual.get(0).getBoundaryId().getBigintValues(),
                "the first chunk starts at the smallest key tuple (1,5)");
        assertArrayEquals(new Long[]{2L, 1L}, actual.get(1).getBoundaryId().getBigintValues(),
                "the terminal boundary is the maximum tuple (2,1), not (2,5)");
    }

    /**
     * The run records the supplied label, SQL text, chunk size and driving table.
     */
    @Test
    void recordsLabelSqlTextAndChunkSize() {
        createSource(1, 2, 3, 4, 5);

        String label = "records-columns-run";
        long runId = populate(label, 5);

        MigrationRunRecord run = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertEquals(label, run.getLabel());
        assertEquals(SQL_TEXT, run.getSqlText());
        assertEquals(5, run.getChunkSize());
        assertEquals(1, run.getThreads().intValue());
        assertEquals(TEST_BIGINT.getSchema().getName(), run.getDrivingTableSchemaName());
        assertEquals(TEST_BIGINT.getName(), run.getDrivingTableName());
    }

    /**
     * The run records the supplied thread count (a non-default value, so the
     * parameter pass-through is exercised and not just the column default).
     */
    @Test
    void recordsTheGivenThreads() {
        String label = LABEL + "-threads";

        long runId = populate(TEST_BIGINT, label, 5, 3);

        MigrationRunRecord run = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertEquals(3, run.getThreads().intValue());
    }

    /**
     * Boundaries are inserted with {@code completed_at} left null.
     */
    @Test
    void boundariesHaveNullCompletedAt() {
        createSource(1, 2, 3, 4);

        long runId = populate(2);

        int completed = dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId).and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
        assertEquals(0, completed, "completed_at should be null for a fresh run");
    }

    /**
     * Populating boundaries records the run's start and the range-calculation
     * completion, and leaves the run's {@code completed_at} null (the chunks
     * have not run yet).
     */
    @Test
    void recordsRunStartAndBoundaryCalculationTimestamps() {
        createSource(1, 2, 3, 4);

        long runId = populate(2);

        MigrationRunRecord run = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(run.getStartedAt(), "started_at is set at the run start");
        assertNotNull(run.getBoundariesCalculatedAt(),
                "boundaries_calculated_at is set when the ranges are ready");
        assertNull(run.getCompletedAt(), "completed_at is null until the chunks run");
        assertFalse(run.getBoundariesCalculatedAt().isBefore(run.getStartedAt()),
                "the range calculation cannot finish before the run starts");
    }

    /**
     * The primary-key column is resolved from the catalog, not assumed to be
     * named {@code id}.
     */
    @Test
    void resolvesThePrimaryKeyColumnNameFromTheCatalog() {
        dsl.insertInto(TEST_KEY, TEST_KEY.KEY).values(1L).values(2L).values(3L).execute();

        long runId = populate(TEST_KEY, 2);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 3}});
    }

    /**
     * A label can be reused by archiving its active run: populate, archive,
     * then populate with the same label again. The first run keeps its
     * boundaries and gets an {@code archived_at}; the second is a new row.
     */
    @Test
    void labelCanBeReusedAfterArchiving() {
        createSource(1, 2, 3, 4);

        String label = LABEL + "-" + ++labelCounter;
        long firstRun = populate(label, 2);

        Long archivedId = Routines.archiveMigrationRun(dsl.configuration(), label);

        long secondRun = populate(label, 2);

        assertEquals(firstRun, archivedId, "archive should return the archived run id");
        assertNotEquals(firstRun, secondRun, "a new run should be created");
        assertTrue(archived(firstRun), "the first run should be archived");
        assertFalse(archived(secondRun), "the second run should be active");
        assertBoundaries(firstRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
        assertBoundaries(secondRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
    }

    /**
     * Archiving a label with no active run returns null.
     */
    @Test
    void archivingAnUnknownLabelReturnsNull() {
        createSource(1, 2);

        assertNull(Routines.archiveMigrationRun(dsl.configuration(), "no-such-label"));
    }

    /**
     * A NULL chunking strategy is rejected before a run is created.
     */
    @Test
    void rejectsANullChunkingStrategy() {
        createSource(1, 2, 3);

        assertSqlState("22023", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(),
                        TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                        LABEL + "-" + ++labelCounter, SQL_TEXT, 2, 1, null, "t", 0, null));
    }

    private void seedOrderedKey() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_ORDERED_KEY,
                            TEST_ORDERED_KEY.ID, TEST_ORDERED_KEY.CREATED_AT,
                            TEST_ORDERED_KEY.EXTERNAL_ID, TEST_ORDERED_KEY.LABEL,
                            TEST_ORDERED_KEY.COLLATED_LABEL, TEST_ORDERED_KEY.NUMERIC_KEY)
                    .values((long) i, timestamp((i + 1) / 2), uuid(i), "k" + i, "k" + i,
                            BigDecimal.valueOf(i))
                    .execute();
        }
    }

    private void assertTimestamptzBigint(MigrationBoundaryRecord boundary, int second, long id) {
        assertEquals(timestamp(second).toInstant(),
                boundary.getBoundaryId().getTimestamptzValues()[0].toInstant(),
                "timestamptz position");
        assertEquals(id, boundary.getBoundaryId().getBigintValues()[1].longValue(),
                "bigint position");
    }

    private long populateOrderedKey() {
        return populate(TEST_ORDERED_KEY, ChunkingStrategy.ordered_key,
                "test_ordered_key_created_at_id_idx");
    }

    private long populate(Table<?> table, ChunkingStrategy chunkBy, String index) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(
                        dsl.configuration(), table.getSchema().getName(), table.getName(),
                        LABEL + "-" + ++labelCounter, SQL_TEXT, 2, 1, chunkBy, "t", 0, index);
    }

    private MigrationRunRecord run(long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }

    private void createSource(long... ids) {
        seedBigint(ids);
    }

    private boolean archived(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.ARCHIVED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.ARCHIVED_AT.isNotNull()));
    }

    private long populate(int chunkSize) {
        return populate(LABEL + "-" + ++labelCounter, chunkSize);
    }

    private long populate(String label, int chunkSize) {
        return populate(TEST_BIGINT, label, chunkSize);
    }

    private long populate(Table<?> table, int chunkSize) {
        return populate(table, LABEL + "-" + ++labelCounter, chunkSize);
    }

    private long populate(Table<?> table, String label, int chunkSize) {
        return populate(table, label, chunkSize, 1);
    }

    private long populate(Table<?> table, String label, int chunkSize, int threads) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), table.getSchema().getName(), table.getName(), label, SQL_TEXT,
                chunkSize, threads, ChunkingStrategy.primary_key, "t", 0, null);
    }

    private List<MigrationBoundaryRecord> boundaries(long runId) {
        return dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
    }

    private void assertBoundaries(long runId, long[][] expected) {
        List<MigrationBoundaryRecord> actual = boundaries(runId);
        assertEquals(expected.length, actual.size(), "boundary count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual.get(i).getBoundaryNo().longValue(),
                    "boundary_no " + i);
            assertEquals(expected[i][1], actual.get(i).getBoundaryId().getBigintValues()[0].longValue(),
                    "boundary_id " + i);
        }
    }

    private int runCount(long runId) {
        return dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.RUN_ID.eq(runId));
    }
}
