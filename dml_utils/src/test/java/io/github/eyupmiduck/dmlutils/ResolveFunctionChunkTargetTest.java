package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ResolveFunctionChunkTarget;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.resolve_function_chunk_target}: a void function
 * whose argument types match the primary key in order resolves to the key columns
 * and a positional {@code t.<column>} call list, while a missing, mistyped,
 * non-void or procedure target is rejected with {@code 22023}.
 */
class ResolveFunctionChunkTargetTest extends PostgresTestBase {

    private ResolveFunctionChunkTarget resolve(String table, String function) {
        return Routines.resolveFunctionChunkTarget(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), table, "public", function);
    }

    private ResolveFunctionChunkTarget resolveBigint(String function) {
        return Routines.resolveFunctionChunkTarget(dsl.configuration(),
                schema(TEST_BIGINT), name(TEST_BIGINT), "public", function);
    }

    /**
     * A two-column function resolves both key columns and the call list in key
     * order.
     */
    @Test
    void resolvesACompositeKeyTarget() {
        dsl.execute("CREATE OR REPLACE FUNCTION public.target_mark_mixed(a bigint, b text)"
                + " RETURNS void LANGUAGE plpgsql AS $$ BEGIN NULL; END $$");

        ResolveFunctionChunkTarget target = resolve(name(TEST_COMPOSITE_MIXED),
                "target_mark_mixed");

        assertArrayEquals(new String[]{"a", "b"}, target.getOPrimaryKeyColumns());
        assertEquals("t.a, t.b", target.getOCallArguments());
    }

    /**
     * A single-column function resolves its key and one call argument.
     */
    @Test
    void resolvesASingleColumnTarget() {
        dsl.execute("CREATE OR REPLACE FUNCTION public.target_mark_bigint(id bigint)"
                + " RETURNS void LANGUAGE plpgsql AS $$ BEGIN NULL; END $$");

        ResolveFunctionChunkTarget target = resolveBigint("target_mark_bigint");

        assertArrayEquals(new String[]{"id"}, target.getOPrimaryKeyColumns());
        assertEquals("t.id", target.getOCallArguments());
    }

    /**
     * A missing function, wrong argument types, a non-void return and a procedure
     * are all rejected with {@code 22023}.
     */
    @Test
    void rejectsInvalidTargets() {
        assertSqlState("22023", () -> resolve(name(TEST_COMPOSITE_MIXED),
                "no_such_function"));

        dsl.execute("CREATE OR REPLACE FUNCTION public.target_mark_wrong(a text)"
                + " RETURNS void LANGUAGE plpgsql AS $$ BEGIN NULL; END $$");
        assertSqlState("22023", () -> resolve(name(TEST_COMPOSITE_MIXED),
                "target_mark_wrong"));

        dsl.execute("CREATE OR REPLACE FUNCTION public.target_mark_nonvoid(a bigint, b text)"
                + " RETURNS bigint LANGUAGE sql AS $$ SELECT a $$");
        assertSqlState("22023", () -> resolve(name(TEST_COMPOSITE_MIXED),
                "target_mark_nonvoid"));

        dsl.execute("CREATE OR REPLACE PROCEDURE public.target_mark_proc(a bigint, b text)"
                + " LANGUAGE plpgsql AS $$ BEGIN NULL; END $$");
        assertSqlState("22023", () -> resolve(name(TEST_COMPOSITE_MIXED),
                "target_mark_proc"));
    }
}
