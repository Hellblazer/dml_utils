package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.BuildChunkBoundaryLiterals;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.build_chunk_boundary_literals}: boundary values
 * become typed SQL literals, the first differing column and its direction are
 * reported (text under the supplied collation), equal values report no
 * difference, and NULL, invalid or malformed values are rejected with
 * {@code 22023}.
 */
class BuildChunkBoundaryLiteralsTest extends PostgresTestBase {

    private BuildChunkBoundaryLiterals literals(String[] columns, String[] kinds,
                                                String[] collations, String[] start,
                                                String[] end) {
        return Routines.buildChunkBoundaryLiterals(dsl.configuration(), columns, kinds,
                collations, start, end);
    }

    /**
     * A bigint range becomes cast literals and reports an ascending difference at
     * the first column.
     */
    @Test
    void buildsBigintLiteralsAndFindsTheFirstDifference() {
        BuildChunkBoundaryLiterals result = literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null},
                new String[]{"1"}, new String[]{"5"});

        assertArrayEquals(new String[]{"'1'::bigint"}, result.getOStartLiterals());
        assertArrayEquals(new String[]{"'5'::bigint"}, result.getOEndLiterals());
        assertEquals(-1, result.getOFirstDifference());
        assertEquals(1, result.getOFirstDifferencePosition());
    }

    /**
     * A reversed bigint range reports a positive difference rather than failing;
     * the caller owns the range check.
     */
    @Test
    void reportsAReversedRangeAsPositive() {
        BuildChunkBoundaryLiterals result = literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null},
                new String[]{"5"}, new String[]{"1"});

        assertEquals(1, result.getOFirstDifference());
        assertEquals(1, result.getOFirstDifferencePosition());
    }

    /**
     * Equal values report no difference and the position is zero.
     */
    @Test
    void reportsNoDifferenceForEqualValues() {
        BuildChunkBoundaryLiterals result = literals(new String[]{"t.id", "t.kind"},
                new String[]{"bigint", "text"}, new String[]{null, "\"C\""},
                new String[]{"1", "a"}, new String[]{"1", "a"});

        assertEquals(0, result.getOFirstDifference());
        assertEquals(0, result.getOFirstDifferencePosition());
    }

    /**
     * A NULL collation (a column not in the catalog) compares with the default
     * collation and still reports the first differing text column.
     */
    @Test
    void comparesTextWithTheDefaultCollationWhenNoneIsGiven() {
        BuildChunkBoundaryLiterals result = literals(
                new String[]{"t.a", "t.b"},
                new String[]{"text", "bigint"},
                new String[]{null, null},
                new String[]{"a", "1"},
                new String[]{"b", "2"});

        assertEquals(-1, result.getOFirstDifference());
        assertEquals(1, result.getOFirstDifferencePosition());
    }

    /**
     * The first differing column wins even when a later column also differs. (The
     * supplied collation is passed through to the comparison; the chosen values
     * differ under every collation, so the order here does not depend on it.)
     */
    @Test
    void findsTheFirstDifferingColumn() {
        BuildChunkBoundaryLiterals result = literals(
                new String[]{"t.a", "t.b", "t.c"},
                new String[]{"bigint", "text", "bigint"},
                new String[]{null, "\"C\"", null},
                new String[]{"1", "a", "9"},
                new String[]{"1", "b", "8"});

        assertEquals(-1, result.getOFirstDifference());
        assertEquals(2, result.getOFirstDifferencePosition());
        assertArrayEquals(new String[]{"'1'::bigint", "'a'::text", "'9'::bigint"},
                result.getOStartLiterals());
        assertArrayEquals(new String[]{"'1'::bigint", "'b'::text", "'8'::bigint"},
                result.getOEndLiterals());
    }

    /**
     * uuid and timestamptz values are validated and rendered with their casts.
     */
    @Test
    void buildsUuidAndTimestamptzLiterals() {
        BuildChunkBoundaryLiterals result = literals(
                new String[]{"t.id", "t.created_at"},
                new String[]{"uuid", "timestamptz"},
                new String[]{null, null},
                new String[]{"00000000-0000-0000-0000-000000000001",
                        "2026-01-01T00:00:00Z"},
                new String[]{"00000000-0000-0000-0000-000000000002",
                        "2026-01-02T00:00:00Z"});

        assertArrayEquals(new String[]{
                "'00000000-0000-0000-0000-000000000001'::uuid",
                "'2026-01-01T00:00:00Z'::timestamptz"}, result.getOStartLiterals());
        assertArrayEquals(new String[]{
                "'00000000-0000-0000-0000-000000000002'::uuid",
                "'2026-01-02T00:00:00Z'::timestamptz"}, result.getOEndLiterals());
        assertEquals(-1, result.getOFirstDifference());
        assertEquals(1, result.getOFirstDifferencePosition());
    }

    /**
     * A NULL boundary value, an invalid literal for its kind and an unsupported
     * or NULL kind are all rejected.
     */
    @Test
    void rejectsNullAndInvalidValues() {
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null}, new String[]{null},
                new String[]{"5"}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null}, new String[]{"1"},
                new String[]{null}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null}, new String[]{"x"},
                new String[]{"5"}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"uuid"}, new String[]{null}, new String[]{"x"},
                new String[]{"00000000-0000-0000-0000-000000000002"}));
        assertSqlState("22023", () -> literals(new String[]{"t.created_at"},
                new String[]{"timestamptz"}, new String[]{null}, new String[]{"x"},
                new String[]{"2026-01-02T00:00:00Z"}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"integer"}, new String[]{null}, new String[]{"1"},
                new String[]{"5"}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{null}, new String[]{null}, new String[]{"1"},
                new String[]{"5"}));
    }

    /**
     * Missing, empty and mismatched arrays are rejected.
     */
    @Test
    void rejectsMalformedArrays() {
        assertSqlState("22023", () -> literals(null, new String[]{"bigint"},
                new String[]{null}, new String[]{"1"}, new String[]{"5"}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"}, null,
                new String[]{null}, new String[]{"1"}, new String[]{"5"}));
        assertSqlState("22023", () -> literals(new String[]{}, new String[]{},
                new String[]{}, new String[]{}, new String[]{}));
        assertSqlState("22023", () -> literals(new String[]{"t.id", "t.id2"},
                new String[]{"bigint"}, new String[]{null}, new String[]{"1"},
                new String[]{"5"}));
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null}, new String[]{"1", "2"},
                new String[]{"5"}));
    }

    /**
     * The collation array must be aligned with the other arrays, and a collation
     * that is not a plain or schema-qualified quoted identifier is rejected before
     * it can reach the COLLATE clause.
     */
    @Test
    void rejectsAMisalignedOrUnsafeCollation() {
        assertSqlState("22023", () -> literals(new String[]{"t.a", "t.b"},
                new String[]{"text", "text"}, new String[]{null},
                new String[]{"a", "x"}, new String[]{"b", "y"}));
        assertSqlState("22023", () -> literals(new String[]{"t.a"},
                new String[]{"text"},
                new String[]{"x\" < '' OR pg_sleep(10)::text < '' OR ''1"},
                new String[]{"a"}, new String[]{"b"}));
    }

    /**
     * A collation whose schema needs quoting is accepted: quote_ident can emit
     * {@code "MySchema".mycoll}, and rejecting that would fail a legitimate
     * catalog collation at render time.
     */
    @Test
    void acceptsACollationQualifiedByAQuotedSchema() {
        BuildChunkBoundaryLiterals result = literals(new String[]{"t.a"},
                new String[]{"text"}, new String[]{"\"pg_catalog\".\"en-x-icu\""},
                new String[]{"a"}, new String[]{"b"});

        assertEquals(-1, result.getOFirstDifference());
        assertEquals(1, result.getOFirstDifferencePosition());
    }

    /**
     * Under a nondeterministic (case-insensitive) collation, byte-different values
     * can compare equal. Equality and direction must both be evaluated under the
     * column's collation, so this reports no difference instead of a bogus +1; for
     * a composite key the comparison falls through to the next column.
     */
    @Test
    void comparesEqualityUnderTheColumnCollation() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
            ownerDsl.execute("CREATE COLLATION dml_utils_fixtures.case_insensitive"
                    + " (provider = icu, locale = 'und-u-ks-level1', deterministic = false)");
            try {
                BuildChunkBoundaryLiterals single = literals(new String[]{"t.a"},
                        new String[]{"text"},
                        new String[]{"dml_utils_fixtures.case_insensitive"},
                        new String[]{"A"}, new String[]{"a"});
                assertEquals(0, single.getOFirstDifference(),
                        "A and a are equal under a case-insensitive collation");
                assertEquals(0, single.getOFirstDifferencePosition());

                BuildChunkBoundaryLiterals composite = literals(
                        new String[]{"t.a", "t.b"},
                        new String[]{"text", "bigint"},
                        new String[]{"dml_utils_fixtures.case_insensitive", null},
                        new String[]{"A", "1"}, new String[]{"a", "5"});
                assertEquals(-1, composite.getOFirstDifference(),
                        "the equal text column is skipped, leaving the bigint");
                assertEquals(2, composite.getOFirstDifferencePosition());
            } finally {
                ownerDsl.execute(
                        "DROP COLLATION IF EXISTS dml_utils_fixtures.case_insensitive");
            }
        }
    }

    /**
     * A bigint literal outside the type's range is a 22023, like any other
     * invalid boundary value.
     */
    @Test
    void rejectsAnOutOfRangeBigint() {
        assertSqlState("22023", () -> literals(new String[]{"t.id"},
                new String[]{"bigint"}, new String[]{null},
                new String[]{"99999999999999999999"}, new String[]{"5"}));
    }

    /**
     * Non-1-D and non-1-based arrays are rejected through raw SQL.
     */
    @Test
    void rejectsNonOneDimensionalArrays() {
        assertSqlState("22023", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.build_chunk_boundary_literals("
                        + "ARRAY[['id']]::text[], '{bigint}'::text[], '{NULL}'::text[],"
                        + " '{1}'::text[], '{5}'::text[])"));
        assertSqlState("22023", () -> dsl.fetch(
                "SELECT * FROM dml_utils_lib.build_chunk_boundary_literals("
                        + "'{id}'::text[], '{bigint}'::text[], '{NULL}'::text[],"
                        + " '[0:0]={1}'::text[], '{5}'::text[])"));
    }
}
