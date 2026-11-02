package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.build_boundary_population_sql}: executing the
 * returned statement inserts contiguous chunk boundaries (one start per chunk
 * plus the terminal high-water boundary) for a primary or ordered key, inserts
 * nothing for an empty table, and rejects bad inputs.
 */
class BuildBoundaryPopulationSqlTest extends PostgresTestBase {

    private static String schema() {
        return TEST_BIGINT.getSchema().getName();
    }

    private static String name() {
        return TEST_BIGINT.getName();
    }

    private static void assertMixed(MigrationBoundaryRecord boundary, long a, String b) {
        assertEquals(a, boundary.getBoundaryId().getBigintValues()[0].longValue(),
                "bigint position");
        assertEquals(b, boundary.getBoundaryId().getTextValues()[1], "text position");
    }

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n));
    }

    private static OffsetDateTime timestamp(int second) {
        return OffsetDateTime.parse(String.format("2026-01-01T00:00:%02dZ", second));
    }

    private static void assertTimestamptzBigint(MigrationBoundaryRecord boundary, int second, long id) {
        assertEquals(timestamp(second).toInstant(),
                boundary.getBoundaryId().getTimestamptzValues()[0].toInstant(),
                "timestamptz position");
        assertEquals(id, boundary.getBoundaryId().getBigintValues()[1].longValue(),
                "bigint position");
    }

    /**
     * Clears the fixture table and the run metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        for (Table<?> table : List.of(TEST_BIGINT, TEST_TEXT, TEST_UUID, TEST_COMPOSITE_PK,
                TEST_COMPOSITE_MIXED, TEST_ORDERED_KEY)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * One start boundary per chunk plus the terminal boundary, contiguous 0..N,
     * with the terminal boundary holding the captured maximum key.
     */
    @Test
    void insertsAStartBoundaryPerChunkPlusTheTerminal() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = insertRun();

        dsl.execute(Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, runId, 2L));

        List<MigrationBoundaryRecord> boundaries = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();

        // Rows 1..5 at chunk size 2: starts at 1, 3 and 5 (boundary_no 0, 1, 2)
        // and a terminal high-water boundary at 5 (boundary_no 3).
        assertEquals(4, boundaries.size(), "three chunk starts plus the terminal");
        long[][] expected = {{0, 1}, {1, 3}, {2, 5}, {3, 5}};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], boundaries.get(i).getBoundaryNo().longValue(), "boundary_no " + i);
            assertEquals(expected[i][1],
                    boundaries.get(i).getBoundaryId().getBigintValues()[0].longValue(), "boundary_id " + i);
        }
    }

    /**
     * An empty table yields no boundaries.
     */
    @Test
    void insertsNothingForAnEmptyTable() {
        long runId = insertRun();

        dsl.execute(Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, runId, 2L));

        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId)));
    }

    /**
     * A NULL run id, a non-positive chunk size, mismatched columns/kinds and an
     * unsupported kind are all rejected with {@code 22023}.
     */
    @Test
    void rejectsBadInputs() {
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, null, 2L));
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, 1L, 0L));
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id", "id2"}, new String[]{"bigint"}, 1L, 2L));
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"int; DROP TABLE src"}, 1L, 2L));
    }

    /**
     * The validation branches that direct callers can reach are rejected with
     * {@code 22023}: empty arrays, a non-1 lower bound, multidimensional arrays,
     * a NULL or empty column name, a NULL kind, and a key longer than three
     * columns.
     */
    @Test
    void rejectsMalformedColumnAndKindArrays() {
        assertBuildRejected("ARRAY[]::name[]", "ARRAY['bigint']::text[]");
        assertBuildRejected("'[0:0]={id}'::name[]", "ARRAY['bigint']::text[]");
        assertBuildRejected("ARRAY[ARRAY['id']]::name[]", "ARRAY['bigint']::text[]");
        assertBuildRejected("ARRAY[NULL]::name[]", "ARRAY['bigint']::text[]");
        assertBuildRejected("ARRAY['']::name[]", "ARRAY['bigint']::text[]");
        assertBuildRejected("ARRAY['id']::name[]", "ARRAY[NULL]::text[]");
        assertBuildRejected("ARRAY['a','b','c','d']::name[]",
                "ARRAY['bigint','bigint','bigint','bigint']::text[]");
    }

    /**
     * A text key is packed into the text attribute and chunked in text order.
     */
    @Test
    void insertsTextKeyBoundaries() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_TEXT, TEST_TEXT.ID).values("k" + String.format("%02d", i)).execute();
        }
        long runId = insertRun(TEST_TEXT);

        executeBuild(TEST_TEXT, new String[]{"id"}, new String[]{"text"}, runId, 2L);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(4, boundaries.size(), "three chunk starts plus the terminal");
        assertEquals("k01", boundaries.get(0).getBoundaryId().getTextValues()[0]);
        assertEquals("k03", boundaries.get(1).getBoundaryId().getTextValues()[0]);
        assertEquals("k05", boundaries.get(2).getBoundaryId().getTextValues()[0]);
        assertEquals("k05", boundaries.get(3).getBoundaryId().getTextValues()[0]);
    }

    /**
     * A uuid key is packed into the uuid attribute, including the terminal
     * high-water boundary, which PostgreSQL cannot compute with min/max.
     */
    @Test
    void insertsUuidKeyBoundaries() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_UUID, TEST_UUID.ID).values(uuid(i)).execute();
        }
        long runId = insertRun(TEST_UUID);

        executeBuild(TEST_UUID, new String[]{"id"}, new String[]{"uuid"}, runId, 2L);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(4, boundaries.size(), "three chunk starts plus the terminal");
        assertEquals(uuid(1), boundaries.get(0).getBoundaryId().getUuidValues()[0]);
        assertEquals(uuid(3), boundaries.get(1).getBoundaryId().getUuidValues()[0]);
        assertEquals(uuid(5), boundaries.get(2).getBoundaryId().getUuidValues()[0]);
        assertEquals(uuid(5), boundaries.get(3).getBoundaryId().getUuidValues()[0]);
    }

    /**
     * A composite key with two bigint columns packs both positions into the
     * bigint array.
     */
    @Test
    void insertsDuplicateKindCompositeKeyBoundaries() {
        dsl.insertInto(TEST_COMPOSITE_PK, TEST_COMPOSITE_PK.A, TEST_COMPOSITE_PK.B)
                .values(1L, 1L)
                .values(1L, 2L)
                .values(2L, 1L)
                .values(2L, 2L)
                .execute();
        long runId = insertRun(TEST_COMPOSITE_PK);

        executeBuild(TEST_COMPOSITE_PK, new String[]{"a", "b"},
                new String[]{"bigint", "bigint"}, runId, 2L);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(3, boundaries.size(), "two chunk starts plus the terminal");
        assertEquals(1L, boundaries.get(0).getBoundaryId().getBigintValues()[0].longValue());
        assertEquals(1L, boundaries.get(0).getBoundaryId().getBigintValues()[1].longValue());
        assertEquals(2L, boundaries.get(1).getBoundaryId().getBigintValues()[0].longValue());
        assertEquals(1L, boundaries.get(1).getBoundaryId().getBigintValues()[1].longValue());
        assertEquals(2L, boundaries.get(2).getBoundaryId().getBigintValues()[0].longValue());
        assertEquals(2L, boundaries.get(2).getBoundaryId().getBigintValues()[1].longValue());
    }

    /**
     * A mixed bigint/text composite key packs the positions into different
     * arrays without shifting either one.
     */
    @Test
    void insertsMixedKindCompositeKeyBoundaries() {
        dsl.insertInto(TEST_COMPOSITE_MIXED, TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B)
                .values(1L, "x")
                .values(1L, "y")
                .values(2L, "x")
                .values(3L, "z")
                .execute();
        long runId = insertRun(TEST_COMPOSITE_MIXED);

        executeBuild(TEST_COMPOSITE_MIXED, new String[]{"a", "b"},
                new String[]{"bigint", "text"}, runId, 2L);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(3, boundaries.size(), "two chunk starts plus the terminal");
        assertMixed(boundaries.get(0), 1L, "x");
        assertMixed(boundaries.get(1), 2L, "x");
        assertMixed(boundaries.get(2), 3L, "z");
    }

    /**
     * A timestamptz/bigint composite ordered key packs each position into its
     * own array and chunks in lexicographic order: the duplicated timestamps are
     * ordered by the bigint tiebreaker, including at the terminal boundary.
     */
    @Test
    void insertsTimestamptzCompositeKeyBoundaries() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_ORDERED_KEY,
                            TEST_ORDERED_KEY.ID, TEST_ORDERED_KEY.CREATED_AT,
                            TEST_ORDERED_KEY.EXTERNAL_ID, TEST_ORDERED_KEY.LABEL,
                            TEST_ORDERED_KEY.COLLATED_LABEL, TEST_ORDERED_KEY.NUMERIC_KEY)
                    .values((long) i, timestamp((i + 1) / 2), uuid(i), "k" + i, "k" + i,
                            BigDecimal.valueOf(i))
                    .execute();
        }
        long runId = insertRun(TEST_ORDERED_KEY);

        executeBuild(TEST_ORDERED_KEY, new String[]{"created_at", "id"},
                new String[]{"timestamptz", "bigint"}, runId, 2L);

        List<MigrationBoundaryRecord> boundaries = boundaries(runId);
        assertEquals(4, boundaries.size(), "three chunk starts plus the terminal");
        assertTimestamptzBigint(boundaries.get(0), 1, 1L);
        assertTimestamptzBigint(boundaries.get(1), 2, 3L);
        assertTimestamptzBigint(boundaries.get(2), 3, 5L);
        assertTimestamptzBigint(boundaries.get(3), 3, 5L);
    }

    private void executeBuild(Table<?> table, String[] columns, String[] kinds, long runId,
                              long chunkSize) {
        dsl.execute(Routines.buildBoundaryPopulationSql(dsl.configuration(),
                table.getSchema().getName(), table.getName(), columns, kinds, runId, chunkSize));
    }

    private void assertBuildRejected(String columns, String kinds) {
        assertSqlState("22023", () -> dsl.fetchOne(
                "SELECT dml_utils_lib.build_boundary_population_sql(?, ?, "
                        + columns + ", " + kinds + ", 1, 2)",
                schema(), name()));
    }

    private List<MigrationBoundaryRecord> boundaries(long runId) {
        return dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
    }

    private long insertRun() {
        return insertRun(TEST_BIGINT);
    }

    private long insertRun(Table<?> table) {
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("build-boundary-test-" + UUID.randomUUID(), "SELECT 1", 1, 1,
                        table.getSchema().getName(), table.getName())
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }
}
