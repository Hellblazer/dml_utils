package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainChunkPlanRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainChunkPlansRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.explain_chunk_plans}: it returns the JSON plans
 * for the three statements a chunked run generates — the boundary-population
 * insert, a non-final chunk and the final chunk — for both chunking strategies,
 * and rejects an invalid template.
 */
class ExplainChunkPlansTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    private static void assertPlanKinds(List<ExplainChunkPlansRecord> plans) {
        assertEquals(3, plans.size(), "one row per generated statement");
        assertEquals("boundary_population", plans.get(0).getOPlanKind());
        assertEquals("chunk_non_final", plans.get(1).getOPlanKind());
        assertEquals("chunk_final", plans.get(2).getOPlanKind());
        for (ExplainChunkPlansRecord plan : plans) {
            assertNotNull(plan.getOSqlText(), "each row carries the SQL it explained");
            assertNotNull(plan.getOPlan(), "each row carries a JSON plan");
        }
    }

    /**
     * A primary-key run yields the boundary-population, non-final and final plans.
     */
    @Test
    void returnsTheThreePrimaryKeyPlans() {
        List<ExplainChunkPlansRecord> plans = Routines.explainChunkPlans(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_bigint", "t",
                new String[]{"id"}, new String[]{"bigint"}, 1000L, ChunkingStrategy.primary_key);

        assertPlanKinds(plans);
    }

    /**
     * A timestamptz/bigint composite key yields the same three plan kinds with
     * timestamp literals in the chunk predicate.
     */
    @Test
    void returnsTheThreePlansForATimestamptzCompositeKey() {
        List<ExplainChunkPlansRecord> plans = Routines.explainChunkPlans(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_ordered_key", "t",
                new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"},
                10L, ChunkingStrategy.primary_key);

        assertPlanKinds(plans);
        assertTrue(plans.get(1).getOSqlText()
                        .contains("'2026-01-01T00:00:00Z'::timestamptz"),
                "the non-final chunk uses the synthetic timestamptz start");
        plans.forEach(plan -> assertEquals("ModifyTable", planNodeType(plan.getOPlan())));
    }

    /**
     * A block run yields the same three plan kinds.
     */
    @Test
    void returnsTheThreeBlockPlans() {
        List<ExplainChunkPlansRecord> plans = Routines.explainChunkPlans(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_bigint", "t",
                new String[]{"ctid"}, new String[]{"bigint"}, 10L, ChunkingStrategy.blocks);

        assertPlanKinds(plans);
    }

    /**
     * A template missing a token is rejected with {@code 22023} before any plan
     * is produced.
     */
    @Test
    void rejectsAnInvalidTemplate() {
        assertSqlState("22023", () -> Routines.explainChunkPlans(
                dsl.configuration(), "UPDATE t SET x = 1", FIXTURE_SCHEMA, "test_bigint", "t",
                new String[]{"id"}, new String[]{"bigint"}, 1000L, ChunkingStrategy.primary_key));
    }

    /**
     * The explicit-boundary form explains exactly one rendered chunk and names it
     * by its finality.
     */
    @Test
    void explainsOneExplicitChunk() {
        List<ExplainChunkPlanRecord> plans = Routines.explainChunkPlan(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_composite_mixed", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"7", "a"}, new String[]{"7", "b"}, false);

        assertEquals(1, plans.size(), "one row for the explicit chunk");
        assertEquals("chunk_non_final", plans.get(0).getOPlanKind());
        assertTrue(plans.get(0).getOSqlText()
                        .contains("t.a = '7'::bigint AND t.b >= 'a'::text AND t.b < 'b'::text"),
                "the shared leading column is a prefix equality");
        assertNotNull(plans.get(0).getOPlan(), "the row carries the JSON plan");
    }

    /**
     * The explicit-boundary explain renders exactly the SQL the runner renders for
     * the same timestamptz chunk.
     */
    @Test
    void explainsTheSameTimestamptzSqlTheRunnerRenders() {
        String[] columns = {"created_at", "id"};
        String[] kinds = {"timestamptz", "bigint"};
        String[] start = {"2026-01-01T00:00:00+00", "2"};
        String[] end = {"2026-01-02T00:00:00+00", "4"};

        String runnerSql = Routines.renderChunkSql(dsl.configuration(), TEMPLATE,
                FIXTURE_SCHEMA, "test_ordered_key", "t", columns, kinds, start, end, false);
        List<ExplainChunkPlanRecord> plans = Routines.explainChunkPlan(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_ordered_key", "t",
                columns, kinds, start, end, false);

        assertEquals(runnerSql, plans.get(0).getOSqlText(),
                "the explain path must explain the runner's exact SQL");
        assertNotNull(plans.get(0).getOPlan(), "the row carries the JSON plan");
    }

    /**
     * A reversed explicit range is rejected with {@code 22023}.
     */
    @Test
    void rejectsAReversedExplicitRange() {
        assertSqlState("22023", () -> Routines.explainChunkPlan(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_composite_mixed", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"9", "b"}, new String[]{"7", "a"}, false));
    }
}
