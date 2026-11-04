package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.BuildChunkBoundaryLiterals;
import org.junit.jupiter.api.Test;

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
     * The first differing column wins even when a later column also differs, and
     * a text comparison uses the supplied collation.
     */
    @Test
    void findsTheFirstDifferingColumnAndUsesTheTextCollation() {
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
