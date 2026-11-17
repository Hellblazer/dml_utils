package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.ExplainFunctionOverTableChunkRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.ExplainFunctionOverTableRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.explain_function_over_table}: it validates a
 * user-supplied void function against the driving table's primary key and
 * returns the EXPLAIN plans of the generated per-row SQL, read-only.
 */
class ExplainFunctionOverTableTest extends PostgresTestBase {

    private static final String FN_SCHEMA = "public";

    /**
     * Clears the fixture table and the migration metadata so each test starts
     * clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_COMPOSITE_MIXED).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
        dsl.truncate(TEST_ORDERED_KEY).execute();
    }

    /**
     * Drops the test functions so a rerun of the class starts clean.
     */
    @AfterEach
    void dropFunctions() {
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_mixed(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.wrong_order(text, bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.bad_return(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_partitioned(bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_ordered_key(bigint)");
    }

    /**
     * A matching void function yields the three plan kinds, and the chunk plans
     * explain the generated per-row call over the primary-key columns.
     */
    @Test
    void returnsTheThreePlansForAMatchingFunction() {
        createFunction("mark_mixed", "done");

        List<ExplainFunctionOverTableRecord> plans = Routines.explainFunctionOverTable(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                FN_SCHEMA, "mark_mixed", 10, null, ChunkingStrategy.primary_key, null, false, null);

        assertEquals(List.of("boundary_population", "chunk_non_final", "chunk_final"),
                plans.stream().map(ExplainFunctionOverTableRecord::getOPlanKind).toList());
        assertEquals("ModifyTable", planNodeType(plans.get(0).getOPlan()),
                "the boundary insert plans as a ModifyTable");
        assertTrue(plans.get(1).getOSqlText().contains("SELECT public.mark_mixed(t.a, t.b)"),
                "the non-final chunk explains the per-row function call");
        assertTrue(plans.get(2).getOSqlText().contains("SELECT public.mark_mixed(t.a, t.b)"),
                "the final chunk explains the per-row function call");
    }

    /**
     * The block strategy plans the block boundary insert and half-open ctid
     * chunk ranges for the per-row function call.
     */
    @Test
    void plansTheBlockStrategy() {
        createFunction("mark_mixed", "done");

        List<ExplainFunctionOverTableRecord> plans = Routines.explainFunctionOverTable(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                FN_SCHEMA, "mark_mixed", 10, null, ChunkingStrategy.blocks, null, false, null);

        assertEquals(List.of("boundary_population", "chunk_non_final", "chunk_final"),
                plans.stream().map(ExplainFunctionOverTableRecord::getOPlanKind).toList());
        assertTrue(plans.get(1).getOSqlText().contains("ctid")
                        && plans.get(1).getOSqlText().contains("SELECT public.mark_mixed(t.a, t.b)"),
                "the chunk plan is a ctid range over the per-row function call");
    }

    /**
     * A non-NULL filter is ANDed onto the chunk plans' range predicate, while
     * the boundary-population plan (which does not use the template) is
     * unaffected.
     */
    @Test
    void includesTheFilterInTheChunkPlans() {
        createFunction("mark_mixed", "done");

        List<ExplainFunctionOverTableRecord> plans = Routines.explainFunctionOverTable(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                FN_SCHEMA, "mark_mixed", 10, "t.b = 'x'", ChunkingStrategy.primary_key, null, false, null);

        assertTrue(plans.get(1).getOSqlText().contains("AND (t.b = 'x'\n)"),
                "the non-final chunk ANDs the filter, closing paren included");
        assertTrue(plans.get(2).getOSqlText().contains("AND (t.b = 'x'\n)"),
                "the final chunk ANDs the filter, closing paren included");
        assertFalse(plans.get(0).getOSqlText().contains("t.b = 'x'"),
                "the boundary-population plan is unaffected");
    }

    /**
     * The call is read-only: it creates no run and does not run the function, so
     * the seeded payloads stay null.
     */
    @Test
    void writesNothing() {
        dsl.insertInto(TEST_COMPOSITE_MIXED, TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B)
                .values(1L, "x")
                .values(1L, "y")
                .values(2L, "x")
                .execute();
        createFunction("mark_mixed", "done");

        Routines.explainFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 10, null, ChunkingStrategy.primary_key, null, false, null);

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN), "no run is created");
        assertEquals(0,
                dsl.selectCount()
                        .from(TEST_COMPOSITE_MIXED)
                        .where(TEST_COMPOSITE_MIXED.PAYLOAD.isNotNull())
                        .fetchOne(0, Integer.class),
                "the function must not run");
    }

    /**
     * A function whose argument types do not match the primary key, in key
     * order, is rejected with {@code 22023}.
     */
    @Test
    void rejectsAMismatchedFunction() {
        dsl.execute("CREATE FUNCTION public.wrong_order(text, bigint) RETURNS void"
                + " LANGUAGE sql AS $$ SELECT NULL::void $$");

        assertSqlState("22023", () -> Routines.explainFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "wrong_order", 10, null, ChunkingStrategy.primary_key, null, false, null));
    }

    /**
     * A function that does not return void is rejected with {@code 22023}.
     */
    @Test
    void rejectsANonVoidFunction() {
        dsl.execute("CREATE FUNCTION public.bad_return(bigint, text) RETURNS integer"
                + " LANGUAGE sql AS $$ SELECT 1 $$");

        assertSqlState("22023", () -> Routines.explainFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "bad_return", 10, null, ChunkingStrategy.primary_key, null, false, null));
    }

    /**
     * A missing function is rejected with {@code 22023} before any plan is
     * produced.
     */
    @Test
    void rejectsAMissingFunction() {
        assertSqlState("22023", () -> Routines.explainFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "no_such_function", 10, null, ChunkingStrategy.primary_key, null, false, null));
    }

    /**
     * The explicit-boundary form explains one chunk of the per-row function call,
     * including the prefix-equality shape the synthetic explain never produces.
     */
    @Test
    void explainsOneExplicitChunk() {
        createFunction("mark_mixed", "done");

        List<ExplainFunctionOverTableChunkRecord> plans = Routines.explainFunctionOverTableChunk(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                FN_SCHEMA, "mark_mixed", new String[]{"7", "a"}, new String[]{"7", "b"}, false, null, ChunkingStrategy.primary_key, null, false, null);

        assertEquals(1, plans.size(), "one row for the explicit chunk");
        assertEquals("chunk_non_final", plans.get(0).getOPlanKind());
        String sql = plans.get(0).getOSqlText();
        assertTrue(sql.contains("SELECT public.mark_mixed(t.a, t.b)"),
                "the plan explains the per-row function call");
        assertTrue(sql.contains("t.a = '7'::bigint AND t.b >= 'a'::text AND t.b < 'b'::text"),
                "the shared leading column is a prefix equality");
    }

    /**
     * A non-NULL filter is ANDed onto the explicit chunk predicate.
     */
    @Test
    void appliesTheFilterToAnExplicitChunk() {
        createFunction("mark_mixed", "done");

        List<ExplainFunctionOverTableChunkRecord> plans = Routines.explainFunctionOverTableChunk(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                FN_SCHEMA, "mark_mixed", new String[]{"7", "a"}, new String[]{"7", "b"}, false, "t.b = 'x'", ChunkingStrategy.primary_key, null, false, null);

        assertTrue(plans.get(0).getOSqlText().contains("AND (t.b = 'x'\n)"),
                "the chunk predicate ANDs the filter, closing paren included");
    }

    /**
     * An ordered_key function explain resolves a named unique index while the
     * function still receives the primary-key columns.
     */
    @Test
    void explainsAnOrderedKeyFunction() {
        createOrderedKeyFunction("mark_ordered_key");

        List<ExplainFunctionOverTableRecord> plans = Routines.explainFunctionOverTable(
                dsl.configuration(), schema(TEST_ORDERED_KEY), name(TEST_ORDERED_KEY),
                FN_SCHEMA, "mark_ordered_key", 10, null, ChunkingStrategy.ordered_key,
                "test_ordered_key_created_at_id_idx", false, null);

        assertEquals(3, plans.size());
        assertTrue(plans.get(1).getOSqlText().contains("SELECT public.mark_ordered_key(t.id)"),
                "the chunk plan still calls the function with the primary-key column");
        assertTrue(plans.get(1).getOSqlText().contains("t.created_at"),
                "the chunk key comes from the distinct ordered key");
    }

    /**
     * A partition-wise synthetic explain renders the selected leaf.
     */
    @Test
    void explainsAPartitionWiseFunction() {
        createPartitionFunction("mark_partitioned");

        List<ExplainFunctionOverTableRecord> plans = Routines.explainFunctionOverTable(
                dsl.configuration(), schema(TEST_PARTITIONED_RANGE), name(TEST_PARTITIONED_RANGE),
                FN_SCHEMA, "mark_partitioned", 10, null, ChunkingStrategy.primary_key,
                null, true, 1);

        assertEquals(3, plans.size());
        assertEquals(1, plans.get(0).getOLeafOrdinal());
        String leafName = plans.get(0).getOLeafName();
        assertNotNull(leafName);
        assertTrue(plans.get(0).getOSqlText().contains(leafName));
        assertTrue(plans.get(1).getOSqlText().contains(leafName));
    }

    /**
     * The explicit form resolves a partition-wise ordered key and renders the
     * selected leaf.
     */
    @Test
    void explainsAnExplicitPartitionWiseOrderedKeyChunk() {
        createPartitionFunction("mark_partitioned");

        List<ExplainFunctionOverTableChunkRecord> plans = Routines.explainFunctionOverTableChunk(
                dsl.configuration(), schema(TEST_PARTITIONED_RANGE), name(TEST_PARTITIONED_RANGE),
                FN_SCHEMA, "mark_partitioned", new String[]{"0"}, new String[]{"100"},
                false, null, ChunkingStrategy.ordered_key,
                "test_partitioned_range_ordered_key_idx", true, 1);

        assertEquals(1, plans.size());
        assertEquals(1, plans.get(0).getOLeafOrdinal());
        String leafName = plans.get(0).getOLeafName();
        assertNotNull(leafName);
        assertTrue(plans.get(0).getOSqlText().contains(leafName));
    }

    /**
     * Creates the per-row void function that appends the given value to the
     * payload of the row matching the primary key.
     *
     * @param functionName the function to create
     * @param value        the value appended to {@code payload}
     */
    private void createFunction(String functionName, String value) {
        dsl.execute("CREATE OR REPLACE FUNCTION public." + functionName
                + "(p_a bigint, p_b text) RETURNS void LANGUAGE sql AS $$"
                + " UPDATE " + schema(TEST_COMPOSITE_MIXED) + "." + name(TEST_COMPOSITE_MIXED)
                + " SET payload = coalesce(payload, '') || " + DSL.inline(value)
                + " WHERE a = p_a AND b = p_b $$");
    }

    private void createPartitionFunction(String functionName) {
        dsl.execute("CREATE OR REPLACE FUNCTION public." + functionName
                + "(p_id bigint) RETURNS void LANGUAGE sql AS $$"
                + " UPDATE " + schema(TEST_PARTITIONED_RANGE) + "."
                + name(TEST_PARTITIONED_RANGE)
                + " SET payload = 'done' WHERE id = p_id $$");
    }

    private void createOrderedKeyFunction(String functionName) {
        dsl.execute("CREATE OR REPLACE FUNCTION public." + functionName
                + "(p_id bigint) RETURNS void LANGUAGE sql AS $$ SELECT NULL::void $$");
    }
}
