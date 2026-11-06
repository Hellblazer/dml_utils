package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code dml_utils_lib.assert_boundary_key_index}: the ordered-key
 * strategy requires an index, the other strategies reject one, and a NULL
 * strategy is rejected.
 */
class AssertBoundaryKeyIndexTest extends PostgresTestBase {

    /**
     * ordered_key needs an index; primary_key and blocks accept none.
     */
    @Test
    void acceptsTheValidStrategyIndexCombinations() {
        Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.ordered_key, "some_index");
        Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.primary_key, null);
        Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.blocks, null);
    }

    /**
     * A NULL strategy, ordered_key without an index, and an index supplied for
     * primary_key or blocks all raise {@code 22023}.
     */
    @Test
    void rejectsInvalidStrategyIndexCombinations() {
        assertSqlState("22023", () -> Routines.assertBoundaryKeyIndex(
                dsl.configuration(), null, null));
        assertSqlState("22023", () -> Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.ordered_key, null));
        assertSqlState("22023", () -> Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.primary_key, "some_index"));
        assertSqlState("22023", () -> Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.blocks, "some_index"));
    }

    /**
     * A blank index name is treated as invalid even when ordered_key is selected,
     * rather than reaching the catalog lookup as a missing index.
     */
    @Test
    void rejectsABlankIndexName() {
        assertSqlState("22023", () -> Routines.assertBoundaryKeyIndex(
                dsl.configuration(), ChunkingStrategy.ordered_key, "   "));
    }
}
