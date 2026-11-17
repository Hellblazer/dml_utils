package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOther.TEST_OTHER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestTimestamptzPk.TEST_TIMESTAMPTZ_PK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.run_function_over_table}: it validates the
 * user-supplied function's signature against the driving table's primary key,
 * builds a per-row template, and runs it chunk by chunk via the base engine,
 * including resume and the {@code set_migration_run_function} mutator.
 */
class RunFunctionOverTableTest extends PostgresTestBase {

    private static final String FN_SCHEMA = PUBLIC_SCHEMA;

    /**
     * A stored input template for runs created directly (not the oracle for the
     * builder; that is asserted against buildFunctionChunkTemplate separately).
     *
     * @param functionName the per-row function
     * @return the template
     */
    private static String template(String functionName) {
        return "SELECT public." + functionName + "(t.a, t.b) FROM <driving_table>"
                + " WHERE <chunking_clause>";
    }

    /**
     * The invalid set-function inputs.
     *
     * @return the alias/filter pairs and their SQLSTATEs
     */
    private static java.util.stream.Stream<Arguments> invalidSetFunctionInputs() {
        return java.util.stream.Stream.of(
                Arguments.of("non-default alias", "setalias", "x", null, "0A000"),
                Arguments.of("blank filter", "setblank", "t", "   ", "22023"));
    }

    /**
     * The targets that cannot be called as one void value per row.
     *
     * @return the rejected target definitions
     */
    private static java.util.stream.Stream<Arguments> uncallableTargets() {
        return java.util.stream.Stream.of(
                Arguments.of("mismatched argument order",
                        "CREATE FUNCTION public.wrong_order(text, bigint) RETURNS void"
                                + " LANGUAGE sql AS $$ SELECT NULL::void $$", "wrong_order"),
                Arguments.of("procedure with a matching signature",
                        "CREATE PROCEDURE public.mark_mixed_proc(p_a bigint, p_b text)"
                                + " LANGUAGE sql AS $$ SELECT 1 $$", "mark_mixed_proc"));
    }

    /**
     * Clears the fixture tables each test drives and the migration metadata, so a
     * run left by an earlier method cannot resume or create a run with a derived
     * label.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        for (Table<?> table : List.of(TEST_BIGINT, TEST_COMPOSITE_MIXED, TEST_OTHER,
                TEST_TIMESTAMPTZ_PK, TEST_PARTITIONED_RANGE, TEST_ORDERED_KEY)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * Drops the test functions so a rerun of the class starts clean.
     */
    @AfterEach
    void dropFunctions() {
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_mixed(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.copy_mixed(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_mixed_2(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_bigint(bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_timestamptz(timestamptz)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_partitioned(bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_ordered_key(bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.wrong_order(text, bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.bad_return(bigint, text)");
        dsl.execute("DROP PROCEDURE IF EXISTS public.mark_mixed_proc(bigint, text)");
    }

    /**
     * A function taking the key's timestamptz type runs over a timestamptz
     * primary key, because the resolver exposes the column's real type.
     */
    @Test
    void appliesAFunctionOverATimestamptzPrimaryKey() {
        dsl.execute("CREATE OR REPLACE FUNCTION public.mark_timestamptz(ts timestamptz)"
                + " RETURNS void LANGUAGE sql AS $$"
                + " UPDATE dml_utils_fixtures.test_timestamptz_pk"
                + " SET payload = coalesce(payload, '') || 'done'"
                + " WHERE created_at = ts $$");
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_TIMESTAMPTZ_PK, TEST_TIMESTAMPTZ_PK.CREATED_AT)
                    .values(timestamp(i))
                    .execute();
        }
        String label = uniqueLabel("fn-timestamptz");

        Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_TIMESTAMPTZ_PK), name(TEST_TIMESTAMPTZ_PK),
                FN_SCHEMA, "mark_timestamptz", 2, 1,
                label, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertTrue(runCompleted(label), "the run should be marked complete");
        assertEquals(5, dsl.fetchCount(TEST_TIMESTAMPTZ_PK,
                TEST_TIMESTAMPTZ_PK.PAYLOAD.eq("done")), "every row is processed exactly once");
        assertEquals(0, dsl.fetchCount(TEST_TIMESTAMPTZ_PK,
                        TEST_TIMESTAMPTZ_PK.PAYLOAD.ne("done")
                                .or(TEST_TIMESTAMPTZ_PK.PAYLOAD.isNull())),
                "a double-processed row would hold the value twice");
    }

    /**
     * A void function whose arguments match the composite primary key is applied
     * to every row, one chunk at a time, and the run completes.
     */
    @Test
    void appliesAMatchingFunctionOverEveryRow() {
        createMixed();
        createMarkMixed("done");
        String label = functionTestLabel("mixed");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, label, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "the function should update every row");
        assertTrue(runCompleted(label), "the run should be marked complete");
        assertEquals(3L, dsl.select(MIGRATION_RUN.TOTAL_ROWS_AFFECTED)
                        .from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.LABEL.eq(label))
                        .fetchOne(MIGRATION_RUN.TOTAL_ROWS_AFFECTED),
                "the SELECT template records the number of rows passed to the function");
    }

    /**
     * A blank filter is rejected at the API boundary instead of being appended as
     * an empty predicate and failing later with a syntax error.
     */
    @Test
    void rejectsABlankFilter() {
        createMixed();
        createMarkMixed("done");

        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "mark_mixed", 2, 1, null, "   ", ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000));
    }

    /**
     * The auto-derived label hashes the filter, so a filter far larger than the
     * btree tuple limit still creates a run instead of failing with an opaque
     * index-size error.
     */
    @Test
    void boundsTheDerivedLabelForALongFilter() {
        createMixed();
        createMarkMixed("done");
        String longFilter = "t.b = 'x' AND " + "1 = 1 AND ".repeat(500) + "1 = 1";

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null,
                longFilter, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        String label = dsl.select(MIGRATION_RUN.LABEL)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.ARCHIVED_AT.isNull())
                .orderBy(MIGRATION_RUN.RUN_ID.desc())
                .limit(1)
                .fetchOne(MIGRATION_RUN.LABEL);
        assertTrue(label.startsWith("function:"), "the derived label keeps its prefix");
        assertTrue(label.length() < 500,
                "the filter is hashed, so the label stays far below the btree limit");
        assertTrue(runCompleted(label), "the run should complete");
    }

    /**
     * The per-chunk sleep is passed through to the base run and stored.
     */
    @Test
    void recordsThePerChunkSleep() {
        createMixed();
        createMarkMixed("done");
        String label = functionTestLabel("sleep");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, label, null,
                ChunkingStrategy.primary_key, 1, 25, null, false, 3, 1000);

        assertEquals(25, dsl.select(MIGRATION_RUN.SLEEP_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.SLEEP_MS), "the sleep should be stored on the run");
        assertTrue(runCompleted(label), "the run should complete");
    }

    /**
     * An omitted label is derived deterministically from the table and function,
     * so a second identical call resumes the same run rather than creating a new
     * one.
     */
    @Test
    void derivesTheLabelAndResumesOnASecondCall() {
        createMixed();
        createMarkMixed("done");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 99, 4, null, null,
                ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        // A literal golden label (not a re-implementation of the production SQL):
        // the six-component shape with a NULL filter.
        String derivedLabel = "function:[\"dml_utils_fixtures\", \"test_composite_mixed\", "
                + "\"public\", \"mark_mixed\", null, \"primary_key\"]";
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(derivedLabel)),
                "both calls share one derived run");
        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "every row should still be updated exactly once");
    }

    /**
     * The derived label is identical from sessions whose search_path differs, so
     * the second call resumes the first run instead of deriving a new label and
     * starting another run.
     */
    @Test
    void derivedLabelIsIndependentOfTheSessionSearchPath() throws Exception {
        createMixed();
        createMarkMixed("done");

        for (String searchPath : List.of("public", "dml_utils_data, public")) {
            try (Connection connection = openTestConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path = " + searchPath);
                DSLContext context = DSL.using(connection, SQLDialect.POSTGRES);
                Routines.runFunctionOverTable(context.configuration(),
                        schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                        FN_SCHEMA, "mark_mixed", 2, 1, null, null,
                        ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);
            }
        }

        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.like("function:%")),
                "both sessions derive the same label and share one run");
    }

    /**
     * An omitted label appends the ordered-key index and the partition flag, in
     * that order: the eight-component label is the one an existing ordered-key or
     * partition-wise run was created with, so a repeated call resumes it.
     */
    @Test
    void derivedLabelIncludesTheOrderedKeyAndPartitionComponents() {
        seedOrderedKeyRows(1L, 2L, 3L);
        createMark("mark_ordered_key", TEST_ORDERED_KEY, "done");
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_ORDERED_KEY),
                name(TEST_ORDERED_KEY), FN_SCHEMA, "mark_ordered_key", 1, 1, null, null,
                ChunkingStrategy.ordered_key, 1, 0,
                "test_ordered_key_created_at_id_idx", false, 3, 1000);

        String orderedLabel = "function:" + dsl.fetchOne("""
                        SELECT pg_catalog.json_build_array(?, ?, ?, ?, CAST(? AS text), ?,
                                                           CAST(? AS text), ?)::text
                        """, schema(TEST_ORDERED_KEY), name(TEST_ORDERED_KEY), FN_SCHEMA,
                "mark_ordered_key", null, "ordered_key",
                "test_ordered_key_created_at_id_idx", false).get(0, String.class);
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(orderedLabel)),
                "the ordered-key run uses the eight-component derived label");

        // A second call with different inputs resumes the same derived run.
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_ORDERED_KEY),
                name(TEST_ORDERED_KEY), FN_SCHEMA, "mark_ordered_key", 99, 4, null, null,
                ChunkingStrategy.ordered_key, 1, 0,
                "test_ordered_key_created_at_id_idx", false, 3, 1000);
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(orderedLabel)),
                "the repeated ordered-key call resumes the same run");

        insertPartitionedRows(50L, 150L, 250L);
        createMark("mark_partitioned", TEST_PARTITIONED_RANGE, "done");
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_PARTITIONED_RANGE),
                name(TEST_PARTITIONED_RANGE), FN_SCHEMA, "mark_partitioned", 1, 1, null, null,
                ChunkingStrategy.primary_key, 1, 0, null, true, 3, 1000);

        String partitionLabel = "function:" + dsl.fetchOne("""
                                SELECT pg_catalog.json_build_array(?, ?, ?, ?, CAST(? AS text), ?,
                                                                   CAST(? AS text), ?)::text
                                """, schema(TEST_PARTITIONED_RANGE), name(TEST_PARTITIONED_RANGE),
                        FN_SCHEMA, "mark_partitioned", null, "primary_key", null, true)
                .get(0, String.class);
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(partitionLabel)),
                "the partition-wise run appends the flag even without an index");
        assertEquals(3, payloadCount(TEST_PARTITIONED_RANGE, "done"),
                "the partition-wise run applied the function to every row");
    }

    /**
     * {@code set_migration_run_function} swaps the function on an unfinished run,
     * changing the stored {@code sql_text} the next call renders.
     */
    @Test
    void setMigrationRunFunctionChangesTheStoredSql() {
        createMixed();
        createMarkMixed("first");
        createMarkMixed2("second");
        String label = functionTestLabel("setfn");

        // Create the run without processing it, then swap in the second function.
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                label, template("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2", null);

        assertTrue(storedSqlText(label).contains("mark_mixed_2"),
                "the stored sql_text should now reference the new function");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed_2", 2, 1, label, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "second"),
                "the run should apply the swapped-in function");
        assertEquals(0, payloadCount(TEST_COMPOSITE_MIXED, "first"),
                "the replaced function must not run");
        assertTrue(runCompleted(label), "the run should be marked complete");
    }

    /**
     * {@code set_migration_run_function} raises {@code P0002} when there is no
     * unfinished run for the label.
     */
    @Test
    void setMigrationRunFunctionRaisesWhenNoUnfinishedRun() {
        createMarkMixed("done");
        assertSqlState("P0002", () -> Routines.setMigrationRunFunction(
                dsl.configuration(), "no-such-label", FN_SCHEMA, "mark_mixed", null));
    }

    /**
     * Setting the function rejects a non-default alias ({@code 0A000}) and a
     * blank filter ({@code 22023}) before either can be persisted.
     *
     * @param description   the rejected input
     * @param labelPrefix   the run label prefix
     * @param alias         the run's driving-table alias
     * @param filter        the function filter
     * @param expectedState the expected SQLSTATE
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSetFunctionInputs")
    void setMigrationRunFunctionRejectsInvalidInput(String description, String labelPrefix,
                                                    String alias, String filter,
                                                    String expectedState) {
        createMixed();
        createMarkMixed("done");
        String label = functionTestLabel(labelPrefix);

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                label, template("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, alias, 0,
                null, false, 3, 1000);

        assertSqlState(expectedState, () -> Routines.setMigrationRunFunction(
                dsl.configuration(), label, FN_SCHEMA, "mark_mixed", filter));
    }

    /**
     * ordered_key requires a named unique index; omitting it fails before a run
     * is created.
     */
    @Test
    void requiresAnIndexForOrderedKeyChunking() {
        createMark("mark_bigint", TEST_BIGINT, "done");
        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), FN_SCHEMA,
                "mark_bigint", 2, 1, null, null, ChunkingStrategy.ordered_key, 1, 0, null, false, 3, 1000));
        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "ordered_key without an index must not create a run");
    }

    /**
     * An ordered_key function run uses the named unique index while still passing
     * the primary-key values to the function.
     */
    @Test
    void appliesAnOrderedKeyFunctionOverEveryRow() {
        seedOrderedKeyRows(1L, 2L, 3L);
        createMark("mark_ordered_key", TEST_ORDERED_KEY, "done");
        String label = functionTestLabel("ordered-key");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_ORDERED_KEY),
                name(TEST_ORDERED_KEY), FN_SCHEMA, "mark_ordered_key", 1, 1, label, null,
                ChunkingStrategy.ordered_key, 1, 0,
                "test_ordered_key_created_at_id_idx", false, 3, 1000);

        assertEquals(3, payloadCount(TEST_ORDERED_KEY, "done"),
                "the function runs once per row while the ordered key drives chunking");
        assertTrue(runCompleted(label), "the run should be complete");
    }

    /**
     * A partition-wise primary-key function run applies the function leaf by
     * leaf while still receiving the primary-key values.
     */
    @Test
    void appliesAPartitionWiseFunctionOverEveryLeaf() {
        insertPartitionedRows(50L, 150L, 250L);
        createMark("mark_partitioned", TEST_PARTITIONED_RANGE, "done");
        String label = functionTestLabel("partition-primary");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_PARTITIONED_RANGE),
                name(TEST_PARTITIONED_RANGE), FN_SCHEMA, "mark_partitioned", 1, 1, label, null,
                ChunkingStrategy.primary_key, 1, 0, null, true, 3, 1000);

        assertEquals(3, payloadCount(TEST_PARTITIONED_RANGE, "done"),
                "the function runs once per row across the leaves");
        assertTrue(runCompleted(label), "the run should be complete");
    }

    /**
     * A partition-wise ordered-key function run uses the parent unique index and
     * applies the function inside each leaf.
     */
    @Test
    void appliesAPartitionWiseOrderedKeyFunctionOverEveryLeaf() {
        insertPartitionedRows(50L, 150L, 250L);
        createMark("mark_partitioned", TEST_PARTITIONED_RANGE, "done");
        String label = functionTestLabel("partition-ordered");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_PARTITIONED_RANGE),
                name(TEST_PARTITIONED_RANGE), FN_SCHEMA, "mark_partitioned", 1, 1, label, null,
                ChunkingStrategy.ordered_key, 1, 0,
                "test_partitioned_range_ordered_key_idx", true, 3, 1000);

        assertEquals(3, payloadCount(TEST_PARTITIONED_RANGE, "done"),
                "the function runs once per row across the leaves");
        assertTrue(runCompleted(label), "the run should be complete");
    }

    /**
     * A function whose argument types are in the wrong order, and a procedure
     * with a matching signature, are rejected with {@code 22023} and create no
     * run.
     *
     * @param description  the rejected target
     * @param definition   the CREATE statement for the target
     * @param functionName the target name
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("uncallableTargets")
    void rejectsTargetsThatCannotBeCalledPerRow(String description, String definition,
                                                String functionName) {
        dsl.execute(definition);

        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                functionName, 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0, null,
                false, 3, 1000));
        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "a rejected target must not create a run");
    }

    /**
     * A function that does not return void is rejected with {@code 22023}.
     */
    @Test
    void rejectsAFunctionThatDoesNotReturnVoid() {
        dsl.execute("CREATE FUNCTION public.bad_return(bigint, text) RETURNS integer"
                + " LANGUAGE sql AS $$ SELECT 1 $$");

        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "bad_return", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000));
    }


    /**
     * A missing function is rejected with {@code 22023}.
     */
    @Test
    void rejectsAMissingFunction() {
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "no_such_function", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000));
    }

    /**
     * The template the wrapper builds calls the function once per row with the
     * primary-key columns as arguments, in key order.
     */
    @Test
    void buildsThePerRowFunctionTemplate() {
        createMarkMixed("done");

        String template = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .buildFunctionChunkTemplate(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                        name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", null);

        assertEquals("SELECT public.mark_mixed(t.a, t.b) FROM <driving_table>"
                + " WHERE <chunking_clause>", template);
    }

    /**
     * The template builder appends a non-NULL filter as an extra ANDed
     * predicate.
     */
    @Test
    void buildsThePerRowFunctionTemplateWithAFilter() {
        createMarkMixed("done");

        String template = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .buildFunctionChunkTemplate(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                        name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", "t.b = 'x'");

        assertEquals("SELECT public.mark_mixed(t.a, t.b) FROM <driving_table>"
                        + " WHERE <chunking_clause> AND (t.b = 'x'\n)", template,
                "the closing paren is on its own line so a trailing line comment "
                        + "cannot swallow it");
    }

    /**
     * A non-NULL filter is ANDed onto every chunk, so only the rows it matches
     * are passed to the function.
     */
    @Test
    void appliesTheFilterOverOnlyMatchingRows() {
        createMixed();
        createMarkMixed("done");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, "t.b = 'x'", ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEquals(2, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "only the two rows with b = x are updated");
    }

    /**
     * A larger coordinator poll timeout is passed through to
     * {@code run_migration_chunks} and still processes every row.
     */
    @Test
    void runFunctionOverTablePassesThroughThePollTimeout() {
        createMixed();
        createMarkMixed("done");
        String label = functionTestLabel("poll-timeout");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, label, null,
                ChunkingStrategy.primary_key, 50, 0, null, false, 3, 1000);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"), "every row should be updated");
        assertTrue(runCompleted(label), "the run should be marked complete");
    }

    /**
     * The wrapper forwards the initial retry policy to the base engine and stores
     * it on the run.
     */
    @Test
    void runFunctionOverTablePassesThroughTheRetryPolicy() {
        createMixed();
        createMarkMixed("done");
        String label = functionTestLabel("retry-policy");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, label, null,
                ChunkingStrategy.primary_key, 1, 0, null, false, 5, 250);

        assertEquals(5, dsl.select(MIGRATION_RUN.MAX_RETRIES)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.MAX_RETRIES), "max_retries is forwarded");
        assertEquals(250, dsl.select(MIGRATION_RUN.RETRY_BACKOFF_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.RETRY_BACKOFF_MS), "retry_backoff_ms is forwarded");
    }

    /**
     * The derived label includes the filter, so two calls that differ only in
     * their filter create two runs instead of the second resuming the first.
     */
    @Test
    void derivesADifferentLabelWhenTheFilterDiffers() {
        createMixed();
        createMarkMixed("done");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, "t.b = 'x'", ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, "t.b = 'y'", ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEquals(2, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.like("function:%")),
                "a different filter derives a different run");
    }

    /**
     * {@code set_migration_run_function} stores the filter in the run's
     * template, and a later run applies it.
     */
    @Test
    void setMigrationRunFunctionAppliesTheFilter() {
        createMixed();
        createMarkMixed("first");
        createMarkMixed2("second");
        String label = functionTestLabel("setfilter");

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                label, template("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2",
                "t.b = 'x'");

        assertTrue(storedSqlText(label).contains("mark_mixed_2"),
                "the stored sql_text references the new function");
        assertTrue(storedSqlText(label).contains("AND (t.b = 'x'\n)"),
                "the stored sql_text carries the filter, closing paren included");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed_2", 2, 1, label, null, ChunkingStrategy.primary_key, 1, 0, null, false, 3, 1000);

        assertEquals(2, payloadCount(TEST_COMPOSITE_MIXED, "second"),
                "only the filtered rows run the swapped-in function");
        assertEquals(0, payloadCount(TEST_COMPOSITE_MIXED, "first"),
                "the replaced function must not run");
    }

    /**
     * A NULL filter removes any filter from the stored template.
     */
    @Test
    void setMigrationRunFunctionRemovesTheFilter() {
        createMarkMixed("first");
        createMarkMixed2("second");
        String label = functionTestLabel("removefilter");

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                label, template("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2",
                "t.b = 'x'");
        assertTrue(storedSqlText(label).contains("AND (t.b = 'x'\n)"),
                "the filter is stored, closing paren included");

        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2",
                null);

        assertFalse(storedSqlText(label).contains("AND ("),
                "a NULL filter removes the filter from the stored template");
    }

    /**
     * A function that only reads the driving table (it inserts into another table)
     * runs with the block strategy, which is valid because the function leaves the
     * driving table untouched.
     */
    @Test
    void runsAReadOnlyFunctionWithTheBlockStrategy() {
        createMixed();
        dsl.execute("CREATE OR REPLACE FUNCTION public.copy_mixed(p_a bigint, p_b text)"
                + " RETURNS void LANGUAGE sql AS $$"
                + " INSERT INTO dml_utils_fixtures.test_other (id, payload)"
                + " VALUES (hashtext(p_a::text || ':' || p_b)::bigint, p_b) $$");
        String label = functionTestLabel("block");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "copy_mixed", 1, 1, label, null,
                ChunkingStrategy.blocks, 1, 0, null, false, 3, 1000);

        assertEquals(3, dsl.fetchCount(TEST_OTHER), "the function runs once per row");
        assertEquals(3, dsl.fetchCount(TEST_COMPOSITE_MIXED), "the source table is unchanged");
        assertTrue(runCompleted(label), "the run should be complete");
    }

    private void seedOrderedKeyRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_ORDERED_KEY,
                            TEST_ORDERED_KEY.ID,
                            TEST_ORDERED_KEY.CREATED_AT,
                            TEST_ORDERED_KEY.EXTERNAL_ID,
                            TEST_ORDERED_KEY.LABEL,
                            TEST_ORDERED_KEY.COLLATED_LABEL,
                            TEST_ORDERED_KEY.NUMERIC_KEY)
                    .values(id, timestamp(id.intValue()), uuid(id.intValue()),
                            "label-" + id, "collated-" + id, BigDecimal.valueOf(id))
                    .execute();
        }
    }

    private void insertPartitionedRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_PARTITIONED_RANGE, TEST_PARTITIONED_RANGE.ID,
                            TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(id, null)
                    .execute();
        }
    }

    /**
     * Creates a single-key void function that appends a value to the row's
     * payload, so processing a row twice is observable.
     *
     * @param functionName the function name in the function schema
     * @param table        the driving table
     * @param value        the value to append
     */
    private void createMark(String functionName, Table<?> table, String value) {
        dsl.execute("CREATE OR REPLACE FUNCTION public." + functionName + "(p_id bigint)"
                + " RETURNS void LANGUAGE sql AS $$"
                + " UPDATE " + schema(table) + "." + name(table)
                + " SET payload = coalesce(payload, '') || " + DSL.inline(value)
                + " WHERE id = p_id $$");
    }

    private void createMixed() {
        dsl.insertInto(TEST_COMPOSITE_MIXED, TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B)
                .values(1L, "x")
                .values(1L, "y")
                .values(2L, "x")
                .execute();
    }

    private void createMarkMixed(String value) {
        createCompositeMarkFunction("mark_mixed", value);
    }

    private void createMarkMixed2(String value) {
        createCompositeMarkFunction("mark_mixed_2", value);
    }


    private String functionTestLabel(String suffix) {
        return uniqueLabel("function-test-" + suffix);
    }

    private String storedSqlText(String label) {
        return dsl.select(MIGRATION_RUN.SQL_TEXT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.SQL_TEXT);
    }

    /**
     * The filter spliced into the function template is validated and safely
     * wrapped: a whitespace-only filter, a template token or a reserved sentinel
     * character is rejected, and a trailing {@code --} line comment cannot swallow
     * the closing paren.
     */
    @Test
    void validatesAndSafelyWrapsTheFunctionFilter() {
        createMixed();
        createMarkMixed("done");

        assertSqlState("22023", () -> buildTemplate("\t"));
        assertSqlState("22023", () -> buildTemplate("t.a > 0 AND <driving_table>"));
        assertSqlState("22023", () -> buildTemplate("t.a > 0 AND <chunking_clause>"));
        assertSqlState("22023", () -> buildTemplate("t.a > 0 \u0001"));

        String template = buildTemplate("t.a > 0 -- trailing comment");
        assertTrue(template.endsWith("\n)"),
                () -> "a line comment must not swallow the closing paren: " + template);
    }

    /**
     * Builds the function chunk template for the mixed fixture with a filter.
     *
     * @param filter the filter SQL, or {@code null}
     * @return the template
     */
    private String buildTemplate(String filter) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .buildFunctionChunkTemplate(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                        name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", filter);
    }
}
