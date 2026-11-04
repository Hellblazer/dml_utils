package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainBlockChunkPlansRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainKeyChunkPlansRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the strategy-specific explain helpers: each returns a boundary-insert
 * plan followed by a non-final and final chunk plan, and the rendered chunk SQL
 * uses the expected block or key range.
 */
class ExplainChunkPlanHelpersTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * The blocks helper returns the boundary insert and two half-open ctid chunks.
     */
    @Test
    void explainsBlocksPlans() {
        List<ExplainBlockChunkPlansRecord> plans = Routines.explainBlockChunkPlans(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT),
                "t", 10L);

        assertEquals(3, plans.size());
        assertEquals(List.of("boundary_population", "chunk_non_final", "chunk_final"),
                plans.stream().map(ExplainBlockChunkPlansRecord::getOPlanKind).toList());
        assertTrue(plans.get(0).getOSqlText()
                .contains("INSERT INTO dml_utils_data.migration_boundary"));
        assertTrue(plans.get(1).getOSqlText().contains("t.ctid"));
        assertTrue(plans.get(2).getOSqlText().contains("t.ctid"));
        for (ExplainBlockChunkPlansRecord plan : plans) {
            assertNotNull(plan.getOPlan(), "every row carries a JSON plan");
        }
    }

    /**
     * The key helper returns the boundary insert and two key-range chunks.
     */
    @Test
    void explainsKeyPlans() {
        List<ExplainKeyChunkPlansRecord> plans = Routines.explainKeyChunkPlans(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT),
                "t", new String[]{"id"}, new String[]{"bigint"}, 2L);

        assertEquals(3, plans.size());
        assertEquals(List.of("boundary_population", "chunk_non_final", "chunk_final"),
                plans.stream().map(ExplainKeyChunkPlansRecord::getOPlanKind).toList());
        assertTrue(plans.get(0).getOSqlText()
                .contains("INSERT INTO dml_utils_data.migration_boundary"));
        assertTrue(plans.get(1).getOSqlText().contains("t.id"));
        assertTrue(plans.get(2).getOSqlText().contains("t.id"));
        for (ExplainKeyChunkPlansRecord plan : plans) {
            assertNotNull(plan.getOPlan(), "every row carries a JSON plan");
        }
    }
}
