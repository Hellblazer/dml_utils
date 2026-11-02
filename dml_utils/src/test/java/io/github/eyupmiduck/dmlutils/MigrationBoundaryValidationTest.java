package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeFour.TEST_COMPOSITE_FOUR;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeThree.TEST_COMPOSITE_THREE;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestInteger.TEST_INTEGER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNoPk.TEST_NO_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNumeric.TEST_NUMERIC;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestSmallint.TEST_SMALLINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code dml_utils_lib.populate_migration_boundaries} rejects invalid
 * input through its argument domains and its catalog validations, and writes
 * nothing when validation fails.
 */
class MigrationBoundaryValidationTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String NO_SUCH_TABLE = "no_such_table";
    private static final String SQL_TEXT = "SELECT 1";

    @BeforeEach
    void resetFixtures() {
        // The class shares one database across methods, so clear both the fixture
        // data and the migration metadata (boundaries and errors cascade); a run
        // left by an earlier method would otherwise change count assertions or
        // collide on the one-active-run-per-label rule. DELETE, not TRUNCATE:
        // the test role has DML but not TRUNCATE on the migration tables.
        dsl.deleteFrom(MIGRATION_RUN).execute();
        for (Table<?> table : List.of(TEST_BIGINT, TEST_INTEGER, TEST_SMALLINT, TEST_TEXT,
                TEST_UUID, TEST_NO_PK, TEST_COMPOSITE_PK, TEST_COMPOSITE_THREE,
                TEST_COMPOSITE_FOUR, TEST_NUMERIC)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * A null schema name is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSchemaName() {
        assertDomainViolation(() -> populateByNames(null, TEST_BIGINT.getName(), 1));
    }

    /**
     * A null table name is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullTableName() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, null, 1));
    }

    /**
     * A blank table name is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankTableName() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, "   ", 1));
    }

    /**
     * A null label is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullLabel() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), null, SQL_TEXT, 1, 1, ChunkingStrategy.primary_key, "t", 0, null));
    }

    /**
     * A blank label is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankLabel() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), "   ", SQL_TEXT, 1, 1, ChunkingStrategy.primary_key, "t", 0, null));
    }

    /**
     * A null SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSqlText() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), "null-sql-text", null, 1, 1, ChunkingStrategy.primary_key, "t", 0, null));
    }

    /**
     * A blank SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankSqlText() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), "blank-sql-text", "   ", 1, 1, ChunkingStrategy.primary_key, "t", 0, null));
    }

    /**
     * A NULL sleep is rejected with 22023 before any run is created.
     */
    @Test
    void rejectsANullSleep() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(),
                uniqueLabel(), SQL_TEXT, 2, 1, ChunkingStrategy.primary_key, "t", null, null));
    }

    /**
     * A zero chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsZeroChunkSize() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), 0));
    }

    /**
     * A negative chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNegativeChunkSize() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), -1));
    }

    /**
     * A null chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNullChunkSize() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), null));
    }

    /**
     * A zero thread count is rejected by the positive-integer domain.
     */
    @Test
    void rejectsZeroThreads() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), 1, 0));
    }

    /**
     * A negative thread count is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNegativeThreads() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), 1, -1));
    }

    /**
     * A null thread count is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNullThreads() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), 1, null));
    }

    /**
     * An unknown schema fails with invalid_schema_name (3F000).
     */
    @Test
    void rejectsUnknownSchema() {
        assertSqlState("3F000", () -> populateByNames("no_such_schema", TEST_BIGINT.getName(), 1));
    }

    /**
     * An unknown table fails with undefined_table (42P01).
     */
    @Test
    void rejectsUnknownTable() {
        assertSqlState("42P01", () -> populateByNames(FIXTURE_SCHEMA, NO_SUCH_TABLE, 1));
    }

    /**
     * A table without a primary key fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsTableWithoutPrimaryKey() {
        assertSqlState("22023", () -> populateTable(TEST_NO_PK, 1));
    }

    /**
     * A two-column composite primary key is supported: the boundaries are
     * computed.
     */
    @Test
    void acceptsACompositePrimaryKey() {
        dsl.insertInto(TEST_COMPOSITE_PK, TEST_COMPOSITE_PK.A, TEST_COMPOSITE_PK.B)
                .values(1L, 1L).values(1L, 2L).values(2L, 1L).execute();

        long runId = populateTable(TEST_COMPOSITE_PK, 2);

        assertTrue(runId > 0, "a composite primary key should be accepted");
        assertEquals(3, boundaryCount(runId).intValue(),
                "three rows at chunk size two give two chunk starts plus a terminal boundary");
    }

    /**
     * A three-column, mixed-kind primary key is supported.
     */
    @Test
    void acceptsAThreeColumnMixedKindPrimaryKey() {
        dsl.insertInto(TEST_COMPOSITE_THREE,
                        TEST_COMPOSITE_THREE.B, TEST_COMPOSITE_THREE.A, TEST_COMPOSITE_THREE.C)
                .values(1, "x", UUID.randomUUID())
                .values(1, "y", UUID.randomUUID())
                .values(2, "x", UUID.randomUUID())
                .execute();

        long runId = populateTable(TEST_COMPOSITE_THREE, 2);

        assertTrue(runId > 0, "a three-column mixed-kind primary key should be accepted");
        assertEquals(3, boundaryCount(runId).intValue(),
                "three rows at chunk size two give two chunk starts plus a terminal boundary");
    }

    /**
     * A primary key of more than three columns fails with
     * invalid_parameter_value (22023).
     */
    @Test
    void rejectsPrimaryKeyWithMoreThanThreeColumns() {
        assertSqlState("22023", () -> populateTable(TEST_COMPOSITE_FOUR, 1));
    }

    /**
     * A single primary-key column whose type is not supported for chunking
     * fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsUnsupportedPrimaryKeyType() {
        assertSqlState("22023", () -> populateTable(TEST_NUMERIC, 1));
    }

    /**
     * A single integer primary key is supported: the boundaries are computed.
     */
    @Test
    void acceptsAnIntegerPrimaryKey() {
        dsl.insertInto(TEST_INTEGER, TEST_INTEGER.ID).values(1).values(2).values(3).execute();

        long runId = populateTable(TEST_INTEGER, 2);

        assertTrue(runId > 0, "an integer primary key should be accepted");
    }

    /**
     * A single smallint primary key is supported.
     */
    @Test
    void acceptsASmallintPrimaryKey() {
        dsl.insertInto(TEST_SMALLINT, TEST_SMALLINT.ID).values((short) 1).values((short) 2)
                .values((short) 3).execute();

        long runId = populateTable(TEST_SMALLINT, 2);

        assertTrue(runId > 0, "a smallint primary key should be accepted");
    }

    /**
     * The primary-key helper returns the columns in key order for a single-column
     * key, and the index order (not the name order) for a composite key.
     */
    @Test
    void primaryKeyColumnsReturnsColumnsInKeyOrder() {
        assertArrayEquals(new String[]{"id"},
                primaryKeyColumns(TEST_BIGINT),
                "a single-column key returns its one column");
        assertArrayEquals(new String[]{"a", "b"},
                primaryKeyColumns(TEST_COMPOSITE_PK),
                "a composite key returns its columns in key order");
        assertArrayEquals(new String[]{"b", "a", "c"},
                primaryKeyColumns(TEST_COMPOSITE_THREE),
                "key order comes from the index, not the column name");
    }

    /**
     * The key-kind helper reports {@code bigint} for every supported integer
     * primary-key type.
     */
    @Test
    void primaryKeyKindsIsBigintForIntegerTypes() {
        assertArrayEquals(new String[]{"bigint"}, primaryKeyKinds(TEST_SMALLINT));
        assertArrayEquals(new String[]{"bigint"}, primaryKeyKinds(TEST_INTEGER));
        assertArrayEquals(new String[]{"bigint"}, primaryKeyKinds(TEST_BIGINT));
    }

    /**
     * The key-kind helper reports {@code text} for a text primary key.
     */
    @Test
    void primaryKeyKindsIsTextForText() {
        assertArrayEquals(new String[]{"text"}, primaryKeyKinds(TEST_TEXT));
    }

    /**
     * The key-kind helper reports {@code uuid} for a uuid primary key.
     */
    @Test
    void primaryKeyKindsIsUuidForUuid() {
        assertArrayEquals(new String[]{"uuid"}, primaryKeyKinds(TEST_UUID));
    }

    /**
     * The key-kind helper returns one kind per primary-key column, in key order,
     * for a mixed-kind composite key.
     */
    @Test
    void primaryKeyKindsReturnsOneKindPerColumn() {
        assertArrayEquals(new String[]{"bigint", "text", "uuid"},
                primaryKeyKinds(TEST_COMPOSITE_THREE),
                "mixed integer, text and uuid key columns map to bigint, text, uuid");
    }

    /**
     * The key-kind helper rejects a primary-key type that cannot be chunked
     * with {@code 22023}.
     */
    @Test
    void primaryKeyKindsRejectsUnsupportedType() {
        assertSqlState("22023", () -> primaryKeyKinds(TEST_NUMERIC));
    }

    /**
     * The key-kind helper rejects a table without a primary key with
     * {@code 22023}, via its up-front primary-key-columns call.
     */
    @Test
    void primaryKeyKindsRejectsATableWithoutPrimaryKey() {
        assertSqlState("22023", () -> primaryKeyKinds(TEST_NO_PK));
    }

    /**
     * The key-kind helper rejects a primary key of more than three columns with
     * {@code 22023}, via its up-front primary-key-columns call.
     */
    @Test
    void primaryKeyKindsRejectsMoreThanThreeColumns() {
        assertSqlState("22023", () -> primaryKeyKinds(TEST_COMPOSITE_FOUR));
    }

    /**
     * The key-values helper flattens a position-aligned key into one text value
     * per column, picking the array for each position from the kinds.
     */
    @Test
    void migrationKeyValuesFlattensPositionAlignedArrays() {
        String[] values = Routines.migrationKeyValues(
                dsl.configuration(),
                new MigrationKeyRecord(new Long[]{7L, null, null}, new String[]{null, "x", null},
                        new UUID[]{null, null, uuid(1)}, null),
                new String[]{"bigint", "text", "uuid"});

        assertArrayEquals(new String[]{"7", "x", uuid(1).toString()}, values);
    }

    /**
     * The key-values helper flattens a timestamptz key; the text form casts
     * back to the original instant.
     */
    @Test
    void migrationKeyValuesFlattensATimestamptzKey() {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z");

        String[] values = Routines.migrationKeyValues(
                dsl.configuration(),
                new MigrationKeyRecord(null, null, null, new OffsetDateTime[]{timestamp}),
                new String[]{"timestamptz"});

        assertEquals(timestamp.toInstant(), dsl.fetchOne("SELECT ?::timestamptz", values[0])
                .get(0, OffsetDateTime.class).toInstant());
    }

    /**
     * The key-values helper flattens single-column keys and a two-column mixed
     * key, keeping each value at its key position.
     */
    @Test
    void migrationKeyValuesFlattensSingleAndTwoColumnKeys() {
        assertArrayEquals(new String[]{"7"}, Routines.migrationKeyValues(
                        dsl.configuration(),
                        new MigrationKeyRecord(new Long[]{7L}, null, null, null),
                        new String[]{"bigint"}),
                "a single-column bigint key flattens to one value");

        assertArrayEquals(new String[]{"x"}, Routines.migrationKeyValues(
                        dsl.configuration(),
                        new MigrationKeyRecord(null, new String[]{"x"}, null, null),
                        new String[]{"text"}),
                "a single-column text key reads the text array");

        assertArrayEquals(new String[]{"1", "x"}, Routines.migrationKeyValues(
                        dsl.configuration(),
                        new MigrationKeyRecord(new Long[]{1L, null}, new String[]{null, "x"}, null, null),
                        new String[]{"bigint", "text"}),
                "a two-column mixed key keeps each value at its position");
    }

    /**
     * An absent array (NULL) for a used position and a present array with a
     * NULL element there both flatten to a NULL value, so the result keeps the
     * key's arity instead of shifting the remaining values.
     */
    @Test
    void migrationKeyValuesDoesNotShiftAroundAnAbsentOrNullPosition() {
        assertArrayEquals(new String[]{null}, Routines.migrationKeyValues(
                        dsl.configuration(),
                        new MigrationKeyRecord(null, null, null, null),
                        new String[]{"bigint"}),
                "an absent array yields NULL at the position");

        assertArrayEquals(new String[]{null}, Routines.migrationKeyValues(
                        dsl.configuration(),
                        new MigrationKeyRecord(new Long[]{null}, null, null, null),
                        new String[]{"bigint"}),
                "a NULL element yields NULL at the position");

        assertArrayEquals(new String[]{null, "x"}, Routines.migrationKeyValues(
                        dsl.configuration(),
                        new MigrationKeyRecord(null, new String[]{null, "x"}, null, null),
                        new String[]{"text", "text"}),
                "a NULL element does not compact away the later value");
    }

    /**
     * The key-values helper rejects an unsupported kind with {@code 22023}
     * instead of silently returning NULL for that position.
     */
    @Test
    void migrationKeyValuesRejectsUnsupportedKind() {
        assertSqlState("22023", () -> Routines.migrationKeyValues(
                dsl.configuration(),
                new MigrationKeyRecord(new Long[]{1L}, null, null, null),
                new String[]{"numeric"}));
    }

    /**
     * An empty or NULL kinds array is rejected with {@code 22023} instead of
     * silently returning an empty result.
     */
    @Test
    void migrationKeyValuesRejectsEmptyKinds() {
        assertSqlState("22023", () -> Routines.migrationKeyValues(
                dsl.configuration(),
                new MigrationKeyRecord(new Long[]{1L}, null, null, null),
                new String[]{}));
        assertSqlState("22023", () -> Routines.migrationKeyValues(
                dsl.configuration(),
                new MigrationKeyRecord(new Long[]{1L}, null, null, null),
                null));
    }

    /**
     * A failed validation leaves the migration tables untouched.
     */
    @Test
    void writesNothingWhenValidationFails() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(1L).execute();
        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        int boundariesBefore = dsl.fetchCount(MIGRATION_BOUNDARY);

        // A domain failure, an unknown schema/table, and each catalog validation
        // failure must all leave the migration tables untouched.
        assertDomainViolation(() -> populateByNames(null, TEST_BIGINT.getName(), 1));
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), 0));
        assertSqlState("3F000", () -> populateByNames("no_such_schema", TEST_BIGINT.getName(), 1));
        assertSqlState("42P01", () -> populateByNames(FIXTURE_SCHEMA, NO_SUCH_TABLE, 1));
        assertSqlState("22023", () -> populateTable(TEST_NO_PK, 1));
        assertSqlState("22023", () -> populateTable(TEST_COMPOSITE_FOUR, 1));
        assertSqlState("22023", () -> populateTable(TEST_NUMERIC, 1));

        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN), "no run should be written");
        assertEquals(boundariesBefore, dsl.fetchCount(MIGRATION_BOUNDARY),
                "no boundary should be written");
    }

    /**
     * A second run with the same label while the first is active fails with
     * unique_violation (23505) and writes nothing.
     */
    @Test
    void rejectsLabelWithAnActiveRun() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(1L).execute();

        String label = uniqueLabel();
        long firstRun = populateTable(TEST_BIGINT, label, 1);
        int runsAfterFirst = dsl.fetchCount(MIGRATION_RUN);

        assertSqlState("23505", () -> populateTable(TEST_BIGINT, label, 1));

        assertEquals(runsAfterFirst, dsl.fetchCount(MIGRATION_RUN),
                "the rejected run should not be written");
        assertEquals(2, boundaryCount(firstRun).intValue(), "the first run is untouched");
    }

    /**
     * Once the existing run is archived, the label can be reused.
     */
    @Test
    void allowsLabelReuseAfterTheRunIsArchived() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(1L).execute();

        String label = uniqueLabel();
        long firstRun = populateTable(TEST_BIGINT, label, 1);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ARCHIVED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(firstRun))
                .execute();

        long secondRun = populateTable(TEST_BIGINT, label, 1);

        assertNotEquals(firstRun, secondRun, "a new run should be created");
        assertEquals(2, runCount(label), "both runs for the label should exist");
    }

    /**
     * Calls {@code dml_utils_lib.primary_key_columns} for the fixture table.
     */
    private String[] primaryKeyColumns(Table<?> table) {
        return Routines.primaryKeyColumns(
                dsl.configuration(), table.getSchema().getName(), table.getName());
    }

    /**
     * Calls {@code dml_utils_lib.primary_key_kinds} for the fixture table.
     */
    private String[] primaryKeyKinds(Table<?> table) {
        return Routines.primaryKeyKinds(
                dsl.configuration(), table.getSchema().getName(), table.getName());
    }

    private long populateTable(Table<?> table, int chunkSize) {
        return populateTable(table, uniqueLabel(), chunkSize);
    }

    private long populateTable(Table<?> table, String label, int chunkSize) {
        return populateByNames(table.getSchema().getName(), table.getName(), label, chunkSize);
    }

    private long populateByNames(String schema, String table, Integer chunkSize) {
        return populateByNames(schema, table, uniqueLabel(), chunkSize, 1);
    }

    private long populateByNames(String schema, String table, Integer chunkSize, Integer threads) {
        return populateByNames(schema, table, uniqueLabel(), chunkSize, threads);
    }

    private long populateByNames(String schema, String table, String label, Integer chunkSize) {
        return populateByNames(schema, table, label, chunkSize, 1);
    }

    private long populateByNames(String schema, String table, String label, Integer chunkSize,
                                 Integer threads) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), schema, table, label, SQL_TEXT, chunkSize, threads, ChunkingStrategy.primary_key, "t", 0, null);
    }

    private String uniqueLabel() {
        return uniqueLabel("validation-run");
    }

    private int runCount(String label) {
        return dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label));
    }

    private Integer boundaryCount(long runId) {
        return dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId));
    }
}
