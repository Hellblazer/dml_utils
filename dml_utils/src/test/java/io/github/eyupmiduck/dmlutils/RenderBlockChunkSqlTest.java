package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.render_block_chunk_sql}: it substitutes the
 * driving table and a half-open ctid range into the template, and rejects bad
 * ranges and templates.
 */
class RenderBlockChunkSqlTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "SELECT 1 FROM <driving_table> WHERE <chunking_clause>";

    /**
     * The rendered predicate is a half-open ctid range with quoted tid literals.
     */
    @Test
    void rendersAHalfOpenCtidRange() {
        String sql = Routines.renderBlockChunkSql(dsl.configuration(), TEMPLATE,
                PUBLIC_SCHEMA, "some_table", "t", 0L, 1000L);

        assertEquals("SELECT 1 FROM public.some_table t WHERE "
                + "((t.ctid) >= ('(0,0)'::tid) AND (t.ctid) < ('(1000,0)'::tid))", sql);
    }

    /**
     * A NULL, reversed or empty block range is rejected with {@code 22023}.
     */
    @Test
    void rejectsBadRanges() {
        assertDomainViolation(() -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", "t", null, 10L));
        assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", "t", 10L, 10L));
        assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", "t", 10L, 5L));
        assertEquals("SELECT 1 FROM public.some_table t WHERE "
                        + "((t.ctid) >= ('(0,0)'::tid) AND (t.ctid) < ('(4294967295,0)'::tid))",
                Routines.renderBlockChunkSql(dsl.configuration(), TEMPLATE,
                        PUBLIC_SCHEMA, "some_table", "t", 0L, 4294967295L),
                "the highest representable tid block must still be accepted");
        assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", "t", 0L, 4294967296L));
        assertDomainViolation(() -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", "t", -1L, 5L));
    }

    /**
     * A template without the required tokens is rejected with {@code 22023}.
     */
    @Test
    void rejectsABadTemplate() {
        assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                "SELECT 1", PUBLIC_SCHEMA, "some_table", "t", 0L, 10L));
    }

    /**
     * A reserved substitution character in the template or in a quoted identifier
     * is rejected with {@code 22023}, so the sentinel substitution stays
     * unambiguous. Only the template and the formatted identifiers can introduce
     * one; the generated clause is built from integers.
     */
    @Test
    void rejectsReservedSubstitutionCharacters() {
        for (String sentinel : new String[]{"" + (char) 1, "" + (char) 2}) {
            assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                    "SELECT 1 FROM <driving_table> WHERE <chunking_clause> /*" + sentinel + "*/",
                    PUBLIC_SCHEMA, "some_table", "t", 0L, 10L));
            assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                    TEMPLATE, PUBLIC_SCHEMA, "some" + sentinel + "_table", "t", 0L, 10L));
            assertSqlState("22023", () -> Routines.renderBlockChunkSql(dsl.configuration(),
                    TEMPLATE, PUBLIC_SCHEMA, "some_table", "t" + sentinel, 0L, 10L));
        }
    }

    /**
     * A NULL or empty table alias is rejected by the non-null text domain
     * before the body runs.
     */
    @Test
    void rejectsABadAlias() {
        assertDomainViolation(() -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", null, 0L, 10L));
        assertDomainViolation(() -> Routines.renderBlockChunkSql(dsl.configuration(),
                TEMPLATE, PUBLIC_SCHEMA, "some_table", "", 0L, 10L));
    }
}
