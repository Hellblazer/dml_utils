package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.PrepareChunkSqlRender;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeCollated.TEST_COMPOSITE_COLLATED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.prepare_chunk_sql_render}: it validates the
 * template and the key columns and kinds once and returns the
 * run-invariant render context — the template with the driving table and
 * chunking clause left as sentinels, the alias-qualified column names, and each
 * text column's collation, position-aligned with the columns.
 */
class PrepareChunkSqlRenderTest extends PostgresTestBase {

    /**
     * Sentinel that prepare_chunk_sql_render leaves for <driving_table>.
     */
    private static final char DRIVING_TABLE_SENTINEL = 1;
    /**
     * Sentinel that prepare_chunk_sql_render leaves for <chunking_clause>.
     */
    private static final char CLAUSE_SENTINEL = 2;
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET processed = true WHERE <chunking_clause>";

    /**
     * The prepared template keeps a driving-table sentinel so one context can
     * render the parent or any leaf, no longer contains the token, and the column
     * names are the alias-qualified key columns in key order.
     */
    @Test
    void leavesTheDrivingTableAsASentinelAndQuotesTheColumnNames() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"});

        assertEquals("UPDATE " + DRIVING_TABLE_SENTINEL + " SET processed = true WHERE "
                        + CLAUSE_SENTINEL,
                prepared.getOTemplate(),
                "the driving table and chunking clause should be left as sentinels");
        assertFalse(prepared.getOTemplate().contains("<driving_table>"),
                "the driving-table token should be gone");
        assertArrayEquals(new String[]{"t.a", "t.b"}, prepared.getOColumnNames(),
                "the column names should be alias-qualified and in key order");
    }

    /**
     * A text column's collation is resolved from the catalog and aligned with its
     * position, while a non-text column's entry is NULL.
     */
    @Test
    void resolvesAndAlignsTextColumnCollations() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE,
                TEST_COMPOSITE_COLLATED.getSchema().getName(), TEST_COMPOSITE_COLLATED.getName(), "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"});

        String[] collations = prepared.getOKeyCollations();
        assertEquals(2, collations.length, "one collation entry per key column");
        assertNull(collations[0], "a bigint column has no collation entry");
        assertEquals("\"en-x-icu\"", collations[1], "the text column's ICU collation");
    }

    /**
     * A timestamptz column has no collation entry, so the renderer casts and
     * compares it as a timestamp rather than as text.
     */
    @Test
    void acceptsATimestamptzKindWithoutACollation() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE,
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(), "t",
                new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"});

        assertArrayEquals(new String[]{"t.created_at", "t.id"}, prepared.getOColumnNames());
        String[] collations = prepared.getOKeyCollations();
        assertEquals(2, collations.length, "one collation entry per key column");
        assertNull(collations[0], "a timestamptz column has no collation entry");
        assertNull(collations[1], "a bigint column has no collation entry");
    }

    /**
     * A kind outside the whitelist is rejected, since it is interpolated into the
     * generated SQL.
     */
    @Test
    void rejectsAnUnsupportedKeyKind() {
        assertSqlState("22023", () -> Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a"}, new String[]{"int; DROP TABLE src"}));
    }

    /**
     * A template that already contains a reserved substitution character is
     * rejected.
     */
    @Test
    void rejectsATemplateWithASentinelCharacter() {
        String template = "UPDATE <driving_table> SET processed = '" + DRIVING_TABLE_SENTINEL
                + "' WHERE <chunking_clause>";
        assertSqlState("22023", () -> Routines.prepareChunkSqlRender(
                dsl.configuration(), template, "public", "src", "t",
                new String[]{"a"}, new String[]{"bigint"}));
    }

    /**
     * Mismatched primary-key columns and kinds arrays are rejected.
     */
    @Test
    void rejectsMismatchedColumnsAndKinds() {
        assertSqlState("22023", () -> Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint"}));
    }
}
