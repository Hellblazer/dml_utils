package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOther.TEST_OTHER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.run_function_over_table}: it validates the
 * user-supplied function's signature against the driving table's primary key,
 * builds a per-row template, and runs it chunk by chunk via the base engine,
 * including resume and the {@code set_migration_run_function} mutator.
 */
class RunFunctionOverTableTest extends PostgresTestBase {

    private static final String FN_SCHEMA = PUBLIC_SCHEMA;

    private static String TEMPLATE(String functionName) {
        return "SELECT public." + functionName + "(t.a, t.b) FROM <driving_table>"
                + " WHERE <chunking_clause>";
    }

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

    /**
     * Clears the fixture tables each test drives and the migration metadata, so a
     * run left by an earlier method cannot resume or create a run with a derived
     * label.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        for (Table<?> table : List.of(TEST_BIGINT, TEST_COMPOSITE_MIXED, TEST_OTHER)) {
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
        dsl.execute("DROP FUNCTION IF EXISTS public.wrong_order(text, bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.bad_return(bigint, text)");
        dsl.execute("DROP PROCEDURE IF EXISTS public.mark_mixed_proc(bigint, text)");
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
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, label, null, ChunkingStrategy.primary_key, 1, 0);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "the function should update every row");
        assertTrue(runCompleted(label), "the run should be marked complete");
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
                ChunkingStrategy.primary_key, 1, 25);

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
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0);
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 99, 4, null, null,
                ChunkingStrategy.primary_key, 1, 0);

        String derivedLabel = "function:"
                + dsl.fetchOne("SELECT pg_catalog.json_build_array(?, ?, ?, ?, CAST(? AS text), CAST(? AS text))::text",
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                FN_SCHEMA, "mark_mixed", null, "primary_key").get(0, String.class);
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(derivedLabel)),
                "both calls share one derived run");
        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "every row should still be updated exactly once");
    }

    /**
     * The derived label distinguishes inputs that the old delimiter-joined format
     * would have conflated: a name containing a '.' does not shift the component
     * boundaries, so two different table/function tuples derive different labels.
     */
    @Test
    void derivedLabelIsUnambiguousForNamesContainingDelimiters() {
        String first = dsl.fetchOne("SELECT 'function:' || pg_catalog.json_build_array(?, ?, ?, ?)::text",
                "dml_utils_fixtures", "t.a", "public", "fn").get(0, String.class);
        String second = dsl.fetchOne("SELECT 'function:' || pg_catalog.json_build_array(?, ?, ?, ?)::text",
                "dml_utils_fixtures", "t", "public", "fn.a").get(0, String.class);

        assertNotEquals(first, second,
                "tuples with '.' in different components must derive different labels");
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
                label, TEMPLATE("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, "t", 0, null);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2", null);

        assertTrue(storedSqlText(label).contains("mark_mixed_2"),
                "the stored sql_text should now reference the new function");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed_2", 2, 1, label, null, ChunkingStrategy.primary_key, 1, 0);

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
     * ordered_key is rejected because the generated function template binds the
     * primary-key column types, so an ordered key would change the contract.
     */
    @Test
    void rejectsOrderedKeyChunking() {
        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        assertSqlState("0A000", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), FN_SCHEMA,
                "no_such_function", 2, 1, null, null, ChunkingStrategy.ordered_key, 1, 0));
        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "ordered_key must not create a run");
    }

    /**
     * A function whose argument types do not match the primary key, in key
     * order, is rejected up front with {@code 22023} and writes nothing.
     */
    @Test
    void rejectsAFunctionWithMismatchedArguments() {
        dsl.execute("CREATE FUNCTION public.wrong_order(text, bigint) RETURNS void"
                + " LANGUAGE sql AS $$ SELECT NULL::void $$");

        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "wrong_order", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0));
        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "a rejected function must not create a run");
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
                "bad_return", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0));
    }

    /**
     * A procedure that shares the function name and signature is rejected with
     * {@code 22023}: it cannot be called as {@code SELECT schema.name(...)}, so
     * it must not pass validation.
     */
    @Test
    void rejectsAProcedureWithAMatchingSignature() {
        dsl.execute("CREATE PROCEDURE public.mark_mixed_proc(p_a bigint, p_b text)"
                + " LANGUAGE sql AS $$ SELECT 1 $$");

        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "mark_mixed_proc", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0));
        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "a rejected procedure must not create a run");
    }

    /**
     * A missing function is rejected with {@code 22023}.
     */
    @Test
    void rejectsAMissingFunction() {
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "no_such_function", 2, 1, null, null, ChunkingStrategy.primary_key, 1, 0));
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
                + " WHERE <chunking_clause> AND (t.b = 'x')", template);
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
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, "t.b = 'x'", ChunkingStrategy.primary_key, 1, 0);

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
                ChunkingStrategy.primary_key, 50, 0);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"), "every row should be updated");
        assertTrue(runCompleted(label), "the run should be marked complete");
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
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, "t.b = 'x'", ChunkingStrategy.primary_key, 1, 0);
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null, "t.b = 'y'", ChunkingStrategy.primary_key, 1, 0);

        assertEquals(2, dsl.fetchCount(MIGRATION_RUN),
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
                label, TEMPLATE("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, "t", 0, null);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2",
                "t.b = 'x'");

        assertTrue(storedSqlText(label).contains("mark_mixed_2"),
                "the stored sql_text references the new function");
        assertTrue(storedSqlText(label).contains("AND (t.b = 'x')"),
                "the stored sql_text carries the filter");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed_2", 2, 1, label, null, ChunkingStrategy.primary_key, 1, 0);

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
                label, TEMPLATE("mark_mixed"), 2, 1, ChunkingStrategy.primary_key, "t", 0, null);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2",
                "t.b = 'x'");
        assertTrue(storedSqlText(label).contains("AND (t.b = 'x')"),
                "the filter is stored");

        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2",
                null);

        assertFalse(storedSqlText(label).contains("AND ("),
                "a NULL filter removes the filter from the stored template");
    }

    /**
     * A read-only function runs over the table with the block strategy, which is
     * valid because the function leaves the driving table untouched.
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
                ChunkingStrategy.blocks, 1, 0);

        assertEquals(3, dsl.fetchCount(TEST_OTHER), "the function runs once per row");
        assertEquals(3, dsl.fetchCount(TEST_COMPOSITE_MIXED), "the source table is unchanged");
        assertTrue(runCompleted(label), "the run should be complete");
    }

    private void createMixed() {
        dsl.insertInto(TEST_COMPOSITE_MIXED, TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B)
                .values(1L, "x")
                .values(1L, "y")
                .values(2L, "x")
                .execute();
    }

    private void createMarkMixed(String value) {
        createFunction("mark_mixed", value);
    }

    private void createMarkMixed2(String value) {
        createFunction("mark_mixed_2", value);
    }

    private void createFunction(String functionName, String value) {
        // Append rather than overwrite: a row processed twice would then hold the
        // value twice, so an exact match proves each row was processed once
        // (which a plain assignment could not show). The literal is quoted by
        // DSL.inline, so a value containing a quote cannot break the body.
        dsl.execute("CREATE OR REPLACE FUNCTION public." + functionName
                + "(p_a bigint, p_b text) RETURNS void LANGUAGE sql AS $$"
                + " UPDATE " + schema(TEST_COMPOSITE_MIXED) + "." + name(TEST_COMPOSITE_MIXED)
                + " SET payload = coalesce(payload, '') || " + DSL.inline(value)
                + " WHERE a = p_a AND b = p_b $$");
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

    private int payloadCount(Table<?> table, String value) {
        return dsl.selectCount()
                .from(table)
                .where(field("payload", String.class).eq(value))
                .fetchOne(0, Integer.class);
    }
}
