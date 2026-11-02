package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeCollated.TEST_COMPOSITE_COLLATED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeThree.TEST_COMPOSITE_THREE;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.assert_chunking_template} and
 * {@code dml_utils_lib.render_chunk_sql}: the template tokens must appear
 * exactly once, and substitution produces the expected driving-table reference
 * and a disjunction of axis-aligned boxes that tiles the half-open (or
 * inclusive, for the final chunk) key range, with an explicitly cast key
 * literal per column.
 */
class RenderChunkSqlTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET processed = true WHERE <chunking_clause>";

    /**
     * A valid template is accepted.
     */
    @Test
    void acceptsAValidTemplate() {
        Routines.assertChunkingTemplate(dsl.configuration(), TEMPLATE);
    }

    /**
     * A template missing {@code <driving_table>} is rejected with 22023.
     */
    @Test
    void rejectsMissingDrivingTableToken() {
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(), "UPDATE t SET x = true WHERE <chunking_clause>"));
    }

    /**
     * A template missing {@code <chunking_clause>} is rejected with 22023.
     */
    @Test
    void rejectsMissingChunkingClauseToken() {
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(), "UPDATE <driving_table> SET x = true"));
    }

    /**
     * A token appearing twice is rejected with 22023.
     */
    @Test
    void rejectsDuplicatedTokens() {
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(),
                "UPDATE <driving_table> SET x = true WHERE <chunking_clause> AND <chunking_clause>"));
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(),
                "UPDATE <driving_table> JOIN <driving_table> ON true WHERE <chunking_clause>"));
    }

    /**
     * Substitution produces the qualified, aliased driving-table reference and
     * a non-final (half-open) scalar range with an explicit bigint cast. A
     * one-column key renders as a single box.
     */
    @Test
    void rendersNonFinalChunk() {
        String rendered = render(false, "bigint", "10", "20");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= '10'::bigint AND t.id < '20'::bigint)",
                rendered);
    }

    /**
     * Substitution for the final chunk uses an inclusive upper bound.
     */
    @Test
    void rendersFinalChunk() {
        String rendered = render(true, "bigint", "30", "40");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= '30'::bigint AND t.id <= '40'::bigint)",
                rendered);
    }

    @BeforeEach
    void resetFixture() {
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_ORDERED_KEY).execute();
        for (long id = 1; id <= 5; id++) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
    }

    /**
     * The rendered predicate is not just text: executing it against real rows
     * affects exactly the half-open range for a non-final chunk and exactly the
     * inclusive range for a final chunk, including the boundary rows.
     */
    @Test
    void renderedPredicateSelectsTheChunkRowsWhenExecuted() {
        String template = "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
        String sql = Routines.renderChunkSql(dsl.configuration(), template,
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(), "t",
                new String[]{"id"}, new String[]{"bigint"},
                new String[]{"2"}, new String[]{"4"}, false);
        dsl.execute(sql);

        assertEquals(List.of(2L, 3L), updatedIds(),
                "a non-final chunk covers [start, end) - rows exactly at start, not end");

        dsl.truncate(TEST_BIGINT).execute();
        for (long id = 1; id <= 5; id++) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
        String finalChunk = Routines.renderChunkSql(dsl.configuration(), template,
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(), "t",
                new String[]{"id"}, new String[]{"bigint"},
                new String[]{"2"}, new String[]{"4"}, true);
        dsl.execute(finalChunk);

        assertEquals(List.of(2L, 3L, 4L), updatedIds(),
                "a final chunk covers [start, end] - the end row is included");
    }

    /**
     * A rendered (timestamptz, bigint) predicate selects exactly the rows in the
     * half-open range, using the timestamp and then the id tiebreaker: no gaps and
     * no overlap with the next boundary.
     */
    @Test
    void renderedCompositePredicateSelectsTheOrderedKeyRowsWhenExecuted() {
        seedOrderedKey();
        String template = "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
        String sql = Routines.renderChunkSql(dsl.configuration(), template,
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(), "t",
                new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"},
                new String[]{timestamp(1).toString(), "2"},
                new String[]{timestamp(2).toString(), "4"}, false);
        dsl.execute(sql);

        assertEquals(List.of(2L, 3L), updatedOrderedKeyIds(),
                "the non-final chunk covers [(t1, 2), (t2, 4))");

        dsl.truncate(TEST_ORDERED_KEY).execute();
        seedOrderedKey();
        String finalChunk = Routines.renderChunkSql(dsl.configuration(), template,
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(), "t",
                new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"},
                new String[]{timestamp(1).toString(), "2"},
                new String[]{timestamp(2).toString(), "4"}, true);
        dsl.execute(finalChunk);

        assertEquals(List.of(2L, 3L, 4L), updatedOrderedKeyIds(),
                "the final chunk includes the end row without a gap");
    }

    private List<Long> updatedOrderedKeyIds() {
        return dsl.select(TEST_ORDERED_KEY.ID)
                .from(TEST_ORDERED_KEY)
                .where(TEST_ORDERED_KEY.PAYLOAD.eq("x"))
                .orderBy(TEST_ORDERED_KEY.CREATED_AT, TEST_ORDERED_KEY.ID)
                .fetch(TEST_ORDERED_KEY.ID);
    }

    private List<Long> updatedIds() {
        return dsl.select(TEST_BIGINT.ID)
                .from(TEST_BIGINT)
                .where(TEST_BIGINT.PAYLOAD.eq("x"))
                .orderBy(TEST_BIGINT.ID)
                .fetch(TEST_BIGINT.ID);
    }

    /**
     * A final chunk may cover a single row (start equals end), while a non-final
     * chunk with start equal to end, and any reversed range, are rejected: the
     * predicate would match no rows yet the worker still marks the boundary done.
     */
    @Test
    void rejectsEqualNonFinalOrReversedRanges() {
        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id = '40'::bigint)",
                render(true, "bigint", "40", "40"));
        assertSqlState("22023", () -> render(false, "bigint", "40", "40"));
        assertSqlState("22023", () -> render(false, "bigint", "40", "30"));
        assertSqlState("22023", () -> render(true, "bigint", "40", "30"));
    }

    /**
     * A text key is rendered as a quoted literal cast to text.
     */
    @Test
    void rendersTextKeyWithAnExplicitCast() {
        String rendered = render(false, "text", "abc", "def");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= 'abc'::text AND t.id < 'def'::text)",
                rendered);
    }

    /**
     * A uuid key is rendered as a quoted literal cast to uuid.
     */
    @Test
    void rendersUuidKeyWithAnExplicitCast() {
        String start = new UUID(0x1122334455667788L, 0x99aabbccddeeff00L).toString();
        String end = new UUID(0x1122334455667788L, 0x99aabbccddeeff01L).toString();

        String rendered = render(false, "uuid", start, end);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= '11223344-5566-7788-99aa-bbccddeeff00'::uuid"
                        + " AND t.id < '11223344-5566-7788-99aa-bbccddeeff01'::uuid)",
                rendered);
    }

    /**
     * A timestamptz key is rendered as a quoted literal cast to timestamptz, so
     * it compares by instant rather than as text. The column and kind come from a
     * real catalog fixture, so the declaration matches its type.
     */
    @Test
    void rendersTimestamptzKeyWithAnExplicitCast() {
        String rendered = Routines.renderChunkSql(dsl.configuration(),
                "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>",
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(), "t",
                new String[]{"created_at"}, new String[]{"timestamptz"},
                new String[]{"2026-01-01T00:00:00+00"},
                new String[]{"2026-01-02T00:00:00+00"}, false);

        assertEquals(
                "UPDATE dml_utils_fixtures.test_ordered_key t SET payload = 'x'"
                        + " WHERE (t.created_at >= '2026-01-01T00:00:00+00'::timestamptz"
                        + " AND t.created_at < '2026-01-02T00:00:00+00'::timestamptz)",
                rendered);
    }

    /**
     * A timestamptz/bigint composite key renders its two positions with their own
     * casts and keeps the lexicographic box decomposition.
     */
    @Test
    void rendersTimestamptzCompositeKeyAsADisjunctionOfBoxes() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"created_at", "id"}, new String[]{"timestamptz", "bigint"},
                new String[]{"2026-01-01T00:00:00+00", "1"},
                new String[]{"2026-01-02T00:00:00+00", "5"}, false);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.created_at = '2026-01-01T00:00:00+00'::timestamptz"
                        + " AND t.id >= '1'::bigint"
                        + " OR t.created_at > '2026-01-01T00:00:00+00'::timestamptz"
                        + " AND t.created_at < '2026-01-02T00:00:00+00'::timestamptz"
                        + " OR t.created_at = '2026-01-02T00:00:00+00'::timestamptz"
                        + " AND t.id < '5'::bigint)",
                rendered);
    }

    /**
     * A two-column key whose first column differs renders as three boxes (lower
     * tail, middle, upper tail) with one explicitly cast literal per column.
     */
    @Test
    void rendersCompositeKeyAsADisjunctionOfBoxes() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.a = '1'::bigint AND t.b >= 'x'::text"
                        + " OR t.a > '1'::bigint AND t.a < '2'::bigint"
                        + " OR t.a = '2'::bigint AND t.b < 'y'::text)",
                rendered);
    }

    /**
     * A three-column, mixed-kind key whose first column differs renders as five
     * boxes (two lower-tail, middle, two upper-tail), each with explicitly cast
     * literals, in key order.
     */
    @Test
    void rendersAThreeColumnCompositeKeyAsADisjunctionOfBoxes() {
        String startUuid = new UUID(0x1122334455667788L, 0x99aabbccddeeff00L).toString();
        String endUuid = new UUID(0x1122334455667788L, 0x99aabbccddeeff01L).toString();

        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b", "c"}, new String[]{"bigint", "text", "uuid"},
                new String[]{"1", "x", startUuid}, new String[]{"2", "y", endUuid}, false);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.a = '1'::bigint AND t.b > 'x'::text"
                        + " OR t.a = '1'::bigint AND t.b = 'x'::text"
                        + " AND t.c >= '11223344-5566-7788-99aa-bbccddeeff00'::uuid"
                        + " OR t.a > '1'::bigint AND t.a < '2'::bigint"
                        + " OR t.a = '2'::bigint AND t.b < 'y'::text"
                        + " OR t.a = '2'::bigint AND t.b = 'y'::text"
                        + " AND t.c < '11223344-5566-7788-99aa-bbccddeeff01'::uuid)",
                rendered);
    }

    /**
     * A key that differs only in its last column renders as a single box: a
     * half-open (or inclusive, final) scalar range after the fixed prefix.
     */
    @Test
    void rendersAKeyThatDiffersOnlyInItsLastColumnAsOneBox() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b", "c"}, new String[]{"bigint", "bigint", "bigint"},
                new String[]{"7", "8", "9"}, new String[]{"7", "8", "20"}, false);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.a = '7'::bigint AND t.b = '8'::bigint"
                        + " AND t.c >= '9'::bigint AND t.c < '20'::bigint)",
                rendered);
    }

    /**
     * A composite key's final chunk uses an inclusive upper bound, so the
     * captured maximum row is included.
     */
    @Test
    void rendersCompositeFinalChunk() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, true);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.a = '1'::bigint AND t.b >= 'x'::text"
                        + " OR t.a > '1'::bigint AND t.a < '2'::bigint"
                        + " OR t.a = '2'::bigint AND t.b <= 'y'::text)",
                rendered);
    }

    /**
     * A quote inside a text key is escaped by the literal, so it cannot break
     * out of the generated SQL.
     */
    @Test
    void escapesQuotesInTextKeys() {
        String rendered = render(false, "text", "O'Brien", "O'Dad");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= 'O''Brien'::text AND t.id < 'O''Dad'::text)",
                rendered);
    }

    /**
     * A key kind outside the whitelist is rejected, since the kind is
     * interpolated into the generated SQL.
     */
    @Test
    void rejectsAnUnknownKeyKind() {
        assertSqlState("22023", () -> render(false, "int; DROP TABLE src", "1", "2"));
    }

    /**
     * A NULL boundary value is rejected: {@code format('%L', NULL)} would render
     * an unquoted NULL, so the predicate would silently match no rows while the
     * chunk is still marked complete.
     */
    @Test
    void rejectsNullBoundaryValues() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", null}, new String[]{"2", "y"}, false));

        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a"}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{null}, false));
    }

    /**
     * A NULL primary-key column name is rejected: {@code %I} on NULL would
     * render a degenerate identifier that only fails when a worker parses the
     * chunk SQL.
     */
    @Test
    void rejectsANullPrimaryKeyColumnName() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{null}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{"2"}, false));
    }

    /**
     * Arrays of differing length (columns, kinds or values) are rejected, since
     * they cannot be zipped into one row-value comparison.
     */
    @Test
    void rejectsMismatchedArrayLengths() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false));

        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a"}, new String[]{"bigint"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false));
    }

    /**
     * Empty arrays are rejected: {@code array_length} of an empty array is NULL,
     * so a length comparison alone would let them through and render an empty,
     * malformed predicate.
     */
    @Test
    void rejectsEmptyArrays() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{}, new String[]{},
                new String[]{}, new String[]{}, false));
    }

    /**
     * The primary-key names are whatever the caller resolved from the catalog,
     * not hard-coded {@code id}, and they appear in both range bounds.
     */
    @Test
    void usesTheGivenPrimaryKeyName() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "s",
                new String[]{"pk"}, new String[]{"bigint"}, new String[]{"5"}, new String[]{"15"}, false);

        assertEquals(
                "UPDATE public.src s SET processed = true"
                        + " WHERE (s.pk >= '5'::bigint AND s.pk < '15'::bigint)",
                rendered);
    }

    /**
     * An explicit alias is used verbatim in the driving-table reference and the
     * range predicate.
     */
    @Test
    void usesTheGivenAlias() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "src_row",
                new String[]{"id"}, new String[]{"bigint"}, new String[]{"1"}, new String[]{"2"}, false);

        assertEquals(
                "UPDATE public.src src_row SET processed = true"
                        + " WHERE (src_row.id >= '1'::bigint AND src_row.id < '2'::bigint)",
                rendered);
    }

    /**
     * The schema, table, alias and primary-key name are quoted so unusual
     * identifiers (and a substituted value) cannot break the SQL or reintroduce
     * a token.
     */
    @Test
    void quotesIdentifiersAndCannotReintroduceTokens() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(),
                TEMPLATE,
                "my schema",
                "my table",
                "x\"y",
                new String[]{"pk\"col"},
                new String[]{"bigint"},
                new String[]{"1"},
                new String[]{"2"},
                false);

        assertEquals(
                "UPDATE \"my schema\".\"my table\" \"x\"\"y\" SET processed = true"
                        + " WHERE (\"x\"\"y\".\"pk\"\"col\" >= '1'::bigint"
                        + " AND \"x\"\"y\".\"pk\"\"col\" < '2'::bigint)",
                rendered);
    }

    /**
     * An identifier that literally contains a template token does not get
     * rewritten: the substituted values are inserted after both tokens are
     * replaced, so neither substitution can re-match the other's value.
     */
    @Test
    void doesNotRewriteATokenInsideASubstitutedIdentifier() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(),
                TEMPLATE,
                "public",
                "<chunking_clause>",
                "t",
                new String[]{"id"},
                new String[]{"bigint"},
                new String[]{"1"},
                new String[]{"2"},
                false);

        assertEquals(
                "UPDATE public.\"<chunking_clause>\" t SET processed = true"
                        + " WHERE (t.id >= '1'::bigint AND t.id < '2'::bigint)",
                rendered);
    }

    /**
     * A NULL {@code i_is_final} is rejected with 22023 instead of silently
     * taking the non-final (exclusive) branch.
     */
    @Test
    void rejectsANullFinalFlag() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id"}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{"2"}, null));
    }

    /**
     * A boundary value that is not a valid literal for its key kind is rejected
     * with 22023, rather than being emitted and failing when a worker parses it.
     */
    @Test
    void rejectsANonNumericBigintBoundaryValue() {
        assertSqlState("22023", () -> render(false, "bigint", "oops", "2"));
    }

    /**
     * A UUID key with an invalid boundary value is rejected with 22023.
     */
    @Test
    void rejectsAMalformedUuidBoundaryValue() {
        assertSqlState("22023", () -> render(false, "uuid", "not-a-uuid", "2"));
    }

    /**
     * A template that already contains a reserved substitution character is
     * rejected with 22023.
     */
    @Test
    void rejectsATemplateWithASentinelCharacter() {
        String template = "UPDATE <driving_table> SET processed = '" + (char) 1
                + "' WHERE <chunking_clause>";
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), template, "public", "src", "t",
                new String[]{"id"}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{"2"}, false));
    }

    /**
     * A boundary array with a lower bound other than 1 is rejected with 22023,
     * rather than reading the wrong element.
     */
    @Test
    void rejectsANonOneBasedBoundaryArray() {
        assertSqlState("22023", () -> dsl.execute(
                "SELECT dml_utils_lib.render_chunk_sql('" + TEMPLATE + "',"
                        + " 'public', 'src', 't',"
                        + " ARRAY['id']::name[], ARRAY['bigint'],"
                        + " '[0:0]={1}'::text[], '[0:0]={2}'::text[], false)"));
    }

    /**
     * A multidimensional boundary array is rejected with 22023.
     */
    @Test
    void rejectsAMultidimensionalBoundaryArray() {
        assertSqlState("22023", () -> dsl.execute(
                "SELECT dml_utils_lib.render_chunk_sql('" + TEMPLATE + "',"
                        + " 'public', 'src', 't',"
                        + " ARRAY['id']::name[], ARRAY['bigint'],"
                        + " '{{1}}'::text[], '{{2}}'::text[], false)"));
    }

    /**
     * An empty template is rejected up front by the non-null text domain.
     */
    @Test
    void rejectsAnEmptyTemplate() {
        assertDomainViolation(() -> Routines.assertChunkingTemplate(dsl.configuration(), ""));
    }

    /**
     * A NULL columns/kinds/start/end array, or a NULL element inside the kinds
     * array, is rejected with 22023.
     */
    @Test
    void rejectsNullArraysAndNullKindElements() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                null, new String[]{"bigint"}, new String[]{"1"}, new String[]{"2"}, false));
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id"}, null, new String[]{"1"}, new String[]{"2"}, false));
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id"}, new String[]{"bigint"}, null, new String[]{"2"}, false));
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id"}, new String[]{"bigint"}, new String[]{"1"}, null, false));
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id", "id2"}, new String[]{"bigint", null},
                new String[]{"1", "2"}, new String[]{"3", "4"}, false));
    }

    /**
     * An empty or NULL primary-key column name is rejected with 22023 instead of
     * rendering a degenerate quoted identifier.
     */
    @Test
    void rejectsAnEmptyColumnName() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{""}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{"2"}, false));
    }

    /**
     * The split renderer is equivalent to the one-shot renderer: preparing the
     * context once and rendering the chunk from it yields the same SQL as
     * {@code render_chunk_sql}, which composes the same two steps. This also
     * exercises the two routines directly, including their grants.
     */
    @Test
    void preparedRendererMatchesTheOneShotRenderer() {
        String direct = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false);

        String viaPrepared = dsl.fetchOne(
                        "SELECT dml_utils_lib.render_prepared_chunk_sql("
                                + " (r).o_template, (r).o_column_names, ARRAY['bigint', 'text'],"
                                + " (r).o_key_collations, ARRAY['1', 'x'], ARRAY['2', 'y'], false)"
                                + " FROM dml_utils_lib.prepare_chunk_sql_render("
                                + " '" + TEMPLATE + "', 'public', 'src', 't',"
                                + " ARRAY['a', 'b']::name[], ARRAY['bigint', 'text']) AS r")
                .get(0, String.class);

        assertEquals(direct, viaPrepared,
                "prepare + render_prepared must match the one-shot render");
    }

    /**
     * The box disjunction selects exactly the same rows as the tuple-range
     * predicate it replaces, for a two-column key: a straddle at the leading
     * column (lower tail, middle, upper tail), an adjacent-value straddle whose
     * middle box is empty, an equal leading column (single box), a final
     * (inclusive) chunk and an all-equal single-row final chunk.
     */
    @Test
    void boxDisjunctionSelectsTheSameRowsAsTheTupleRangeForATwoColumnKey() {
        dsl.truncate(TEST_COMPOSITE_PK).execute();
        for (long a = 1; a <= 4; a++) {
            for (long b = 1; b <= 4; b++) {
                dsl.insertInto(TEST_COMPOSITE_PK, TEST_COMPOSITE_PK.A, TEST_COMPOSITE_PK.B)
                        .values(a, b).execute();
            }
        }

        String schema = TEST_COMPOSITE_PK.getSchema().getName();
        String table = TEST_COMPOSITE_PK.getName();
        String[] columns = {"a", "b"};
        String[] kinds = {"bigint", "bigint"};

        assertBoxesMatchTupleRange(schema, table, columns, kinds, new String[]{"1", "3"}, new String[]{"3", "2"}, false);
        assertBoxesMatchTupleRange(schema, table, columns, kinds, new String[]{"2", "3"}, new String[]{"3", "1"}, false);
        assertBoxesMatchTupleRange(schema, table, columns, kinds, new String[]{"2", "1"}, new String[]{"2", "3"}, false);
        assertBoxesMatchTupleRange(schema, table, columns, kinds, new String[]{"1", "3"}, new String[]{"3", "2"}, true);
        assertBoxesMatchTupleRange(schema, table, columns, kinds, new String[]{"2", "1"}, new String[]{"2", "3"}, true);
        assertBoxesMatchTupleRange(schema, table, columns, kinds, new String[]{"2", "2"}, new String[]{"2", "2"}, true);
    }

    /**
     * The box disjunction selects exactly the same rows as the tuple-range
     * predicate for a three-column, mixed-kind key whose physical column order
     * differs from the key order: a straddle at the leading column (five boxes),
     * an equal leading column (three boxes), an equal leading and second column
     * (one box), and an inclusive final chunk.
     */
    @Test
    void boxDisjunctionSelectsTheSameRowsAsTheTupleRangeForAThreeColumnKey() {
        dsl.truncate(TEST_COMPOSITE_THREE).execute();
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        for (int b = 1; b <= 3; b++) {
            for (String a : new String[]{"a1", "a2"}) {
                for (UUID c : new UUID[]{first, second}) {
                    dsl.insertInto(TEST_COMPOSITE_THREE,
                                    TEST_COMPOSITE_THREE.A, TEST_COMPOSITE_THREE.B, TEST_COMPOSITE_THREE.C)
                            .values(a, b, c).execute();
                }
            }
        }

        String schema = TEST_COMPOSITE_THREE.getSchema().getName();
        String table = TEST_COMPOSITE_THREE.getName();
        String[] columns = {"b", "a", "c"};
        String[] kinds = {"bigint", "text", "uuid"};

        assertBoxesMatchTupleRange(schema, table, columns, kinds,
                new String[]{"1", "a2", first.toString()},
                new String[]{"3", "a1", second.toString()}, false);
        assertBoxesMatchTupleRange(schema, table, columns, kinds,
                new String[]{"2", "a1", first.toString()},
                new String[]{"2", "a2", second.toString()}, false);
        assertBoxesMatchTupleRange(schema, table, columns, kinds,
                new String[]{"2", "a1", first.toString()},
                new String[]{"2", "a1", second.toString()}, false);
        assertBoxesMatchTupleRange(schema, table, columns, kinds,
                new String[]{"1", "a2", first.toString()},
                new String[]{"3", "a1", second.toString()}, true);
        assertBoxesMatchTupleRange(schema, table, columns, kinds,
                new String[]{"2", "a1", first.toString()},
                new String[]{"2", "a1", second.toString()}, true);
    }

    /**
     * Over many randomly chosen ranges (both final and non-final), the box
     * disjunction selects exactly the same rows as the tuple-range predicate it
     * replaces, for a two-column and a three-column, mixed-kind key. This sweeps
     * every shape of the decomposition, including a key that differs only in its
     * last column and the inclusive final chunk (with and without a fixed
     * prefix).
     */
    @Test
    void boxDisjunctionMatchesTheTupleRangeOverRandomRanges() {
        List<String[]> twoColumnKeys = new ArrayList<>();
        dsl.truncate(TEST_COMPOSITE_PK).execute();
        for (long a = 1; a <= 3; a++) {
            for (long b = 1; b <= 3; b++) {
                dsl.insertInto(TEST_COMPOSITE_PK, TEST_COMPOSITE_PK.A, TEST_COMPOSITE_PK.B)
                        .values(a, b).execute();
                twoColumnKeys.add(new String[]{Long.toString(a), Long.toString(b)});
            }
        }
        assertRandomRangesMatch(TEST_COMPOSITE_PK.getSchema().getName(), TEST_COMPOSITE_PK.getName(),
                new String[]{"a", "b"}, new String[]{"bigint", "bigint"}, twoColumnKeys, 150, 20261009L);

        List<String[]> threeColumnKeys = new ArrayList<>();
        dsl.truncate(TEST_COMPOSITE_THREE).execute();
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        for (int b = 1; b <= 2; b++) {
            for (String a : new String[]{"a1", "a2"}) {
                for (UUID c : new UUID[]{first, second}) {
                    dsl.insertInto(TEST_COMPOSITE_THREE,
                                    TEST_COMPOSITE_THREE.A, TEST_COMPOSITE_THREE.B, TEST_COMPOSITE_THREE.C)
                            .values(a, b, c).execute();
                    threeColumnKeys.add(new String[]{Integer.toString(b), a, c.toString()});
                }
            }
        }
        assertRandomRangesMatch(TEST_COMPOSITE_THREE.getSchema().getName(), TEST_COMPOSITE_THREE.getName(),
                new String[]{"b", "a", "c"}, new String[]{"bigint", "text", "uuid"}, threeColumnKeys, 150, 20261010L);
    }

    /**
     * A mid-table chunk over a multi-column key is estimated accurately rather
     * than as a large fraction of the table: the box disjunction's EXPLAIN row
     * estimate is within a small factor of the actual row count, and the plan
     * stays on the index rather than degrading to a sequential scan. The
     * equivalent single row-value comparison estimates the same chunk an order of
     * magnitude too high, which is what tips a large table into a full scan.
     */
    @Test
    void estimatesAMidTableChunkAccurately() {
        dsl.execute("TRUNCATE dml_utils_fixtures.test_composite_pk");
        dsl.execute("INSERT INTO dml_utils_fixtures.test_composite_pk (a, b)"
                + " SELECT a, b FROM generate_series(1, 100) AS a, generate_series(1, 100) AS b");
        dsl.execute("ANALYZE dml_utils_fixtures.test_composite_pk");

        long[][] ranges = {
                {49, 90, 51, 10},
                {50, 20, 50, 80},
                {50, 80, 51, 20},
                {20, 1, 20, 100}};
        for (long[] range : ranges) {
            String[] start = {Long.toString(range[0]), Long.toString(range[1])};
            String[] end = {Long.toString(range[2]), Long.toString(range[3])};
            String boxSql = Routines.renderChunkSql(dsl.configuration(),
                    "SELECT 1 FROM <driving_table> WHERE <chunking_clause>",
                    "dml_utils_fixtures", "test_composite_pk", "t",
                    new String[]{"a", "b"}, new String[]{"bigint", "bigint"}, start, end, false);
            String tupleSql = "SELECT 1 FROM dml_utils_fixtures.test_composite_pk t WHERE"
                    + " (t.a, t.b) >= (" + range[0] + ", " + range[1] + ")"
                    + " AND (t.a, t.b) < (" + range[2] + ", " + range[3] + ")";

            long actual = countRows(boxSql);
            long boxEstimate = estimateRows(boxSql);
            long tupleEstimate = estimateRows(tupleSql);

            assertTrue(boxEstimate <= Math.max(10, actual * 3),
                    "the box estimate " + boxEstimate + " should be near the actual " + actual);
            assertTrue(tupleEstimate > boxEstimate * 3,
                    "the row-value estimate " + tupleEstimate + " should be far above the boxes' " + boxEstimate);
        }
    }

    /**
     * Under an ICU collation, where the text key order differs from byte order
     * ('_' before letters, 'a' before 'B', ...), the box disjunction still selects
     * exactly the same rows as the tuple-range predicate, because both use the
     * column's collation.
     */
    @Test
    void boxDisjunctionMatchesTheTupleRangeUnderAnIcuCollation() {
        List<String> ordered = dsl.fetch(
                        "SELECT v FROM (VALUES ('B'), ('a'), ('Z'), ('_'), ('0'), ('M'), ('x'), ('e'), ('é')) AS t(v)"
                                + " ORDER BY v COLLATE \"en-x-icu\"")
                .stream().map(record -> record.get(0, String.class)).toList();

        dsl.truncate(TEST_COMPOSITE_COLLATED).execute();
        List<String[]> keys = new ArrayList<>();
        for (long a = 1; a <= 2; a++) {
            for (String b : ordered) {
                dsl.insertInto(TEST_COMPOSITE_COLLATED, TEST_COMPOSITE_COLLATED.A, TEST_COMPOSITE_COLLATED.B)
                        .values(a, b).execute();
                keys.add(new String[]{Long.toString(a), b});
            }
        }

        assertRandomRangesMatch(TEST_COMPOSITE_COLLATED.getSchema().getName(), TEST_COMPOSITE_COLLATED.getName(),
                new String[]{"a", "b"}, new String[]{"bigint", "text"}, keys, 150, 20261011L);
    }

    /**
     * The box disjunction matches the tuple range for uuid keys whose high bit is
     * set, where PostgreSQL's unsigned byte order differs from a signed
     * comparison of the two halves.
     */
    @Test
    void boxDisjunctionMatchesTheTupleRangeForHighBitUuids() {
        List<String> uuids = dsl.fetch(
                        "SELECT v::text FROM (VALUES"
                                + " ('00000000-0000-0000-0000-000000000000'::uuid),"
                                + " ('7fffffff-ffff-ffff-ffff-ffffffffffff'::uuid),"
                                + " ('80000000-0000-0000-0000-000000000000'::uuid),"
                                + " ('ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)) AS t(v)"
                                + " ORDER BY v")
                .stream().map(record -> record.get(0, String.class)).toList();

        dsl.truncate(TEST_COMPOSITE_THREE).execute();
        List<String[]> keys = new ArrayList<>();
        for (int b = 1; b <= 2; b++) {
            for (String c : uuids) {
                dsl.insertInto(TEST_COMPOSITE_THREE,
                                TEST_COMPOSITE_THREE.A, TEST_COMPOSITE_THREE.B, TEST_COMPOSITE_THREE.C)
                        .values("a1", b, UUID.fromString(c)).execute();
                keys.add(new String[]{Integer.toString(b), "a1", c});
            }
        }

        assertRandomRangesMatch(TEST_COMPOSITE_THREE.getSchema().getName(), TEST_COMPOSITE_THREE.getName(),
                new String[]{"b", "a", "c"}, new String[]{"bigint", "text", "uuid"}, keys, 100, 20261012L);
    }

    /**
     * Picks ordered pairs from {@code keys} (which is in key order) with a fixed
     * seed and asserts each renders the same rows as its tuple range.
     */
    private void assertRandomRangesMatch(String schema, String table, String[] columns, String[] kinds,
                                         List<String[]> keys, int iterations, long seed) {
        Random random = new Random(seed);
        for (int i = 0; i < iterations; i++) {
            int lo = random.nextInt(keys.size());
            int hi = random.nextInt(keys.size());
            if (lo > hi) {
                int swap = lo;
                lo = hi;
                hi = swap;
            }
            boolean isFinal = random.nextBoolean();
            if (lo == hi && !isFinal) {
                continue;
            }
            assertBoxesMatchTupleRange(schema, table, columns, kinds, keys.get(lo), keys.get(hi), isFinal);
        }
    }

    /**
     * Renders the chunk twice against the same range and asserts the box
     * disjunction and the equivalent tuple-range predicate select the same rows.
     */
    private void assertBoxesMatchTupleRange(String schema, String table, String[] columns, String[] kinds,
                                            String[] start, String[] end, boolean isFinal) {
        String template = "SELECT " + String.join(", ", columns) + " FROM <driving_table> WHERE <chunking_clause>";
        String boxSql = Routines.renderChunkSql(dsl.configuration(), template,
                schema, table, "t", columns, kinds, start, end, isFinal);

        StringBuilder columnTuple = new StringBuilder();
        StringBuilder startTuple = new StringBuilder();
        StringBuilder endTuple = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                columnTuple.append(", ");
                startTuple.append(", ");
                endTuple.append(", ");
            }
            columnTuple.append("t.").append(columns[i]);
            startTuple.append("'").append(start[i]).append("'::").append(kinds[i]);
            endTuple.append("'").append(end[i]).append("'::").append(kinds[i]);
        }
        String tupleSql = "SELECT " + String.join(", ", columns) + " FROM " + schema + "." + table + " t WHERE ("
                + columnTuple + ") >= (" + startTuple + ") AND (" + columnTuple + ") "
                + (isFinal ? "<=" : "<") + " (" + endTuple + ")";

        assertEquals(rows(tupleSql, columns), rows(boxSql, columns),
                "the box disjunction must select the same rows as the tuple range");
    }

    private long countRows(String selectSql) {
        return dsl.fetch("SELECT count(*) FROM (" + selectSql + ") AS counted")
                .get(0).get(0, Long.class);
    }

    private long estimateRows(String sql) {
        String plan = dsl.fetch("EXPLAIN (FORMAT JSON) " + sql).get(0).get(0, String.class);
        Matcher matcher = Pattern.compile("\"Plan Rows\":\\s*(\\d+)").matcher(plan);
        assertTrue(matcher.find(), "the plan should report a row estimate");
        return Long.parseLong(matcher.group(1));
    }

    private List<String> rows(String sql, String[] columns) {
        return dsl.fetch(sql).stream()
                .map(record -> {
                    StringBuilder row = new StringBuilder();
                    for (String column : columns) {
                        row.append(record.get(column)).append('|');
                    }
                    return row.toString();
                })
                .sorted()
                .toList();
    }

    private String render(boolean isFinal, String keyKind, String startValue, String endValue) {
        return Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id"}, new String[]{keyKind},
                new String[]{startValue}, new String[]{endValue}, isFinal);
    }
}
