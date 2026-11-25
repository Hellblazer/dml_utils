package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies {@code dml_utils_lib.build_chunk_range_boxes}: the lexicographic
 * [start, end) range is decomposed into the same axis-aligned boxes the renderer
 * emits, including the equal-row, last-column and multi-column cases, with an
 * inclusive upper bound for the final chunk.
 */
class BuildChunkRangeBoxesTest extends PostgresTestBase {

    private String[] boxes(String[] columns, String[] start, String[] end,
                           int firstDifferencePosition, Boolean isFinal) {
        return Routines.buildChunkRangeBoxes(dsl.configuration(), columns, start, end,
                firstDifferencePosition, isFinal);
    }

    /**
     * Equal bounds are a single row box, used only for a final chunk.
     */
    @Test
    void rendersAnEqualRowAsOneBox() {
        assertArrayEquals(new String[]{"t.c1 = '1'"},
                boxes(new String[]{"t.c1"}, new String[]{"'1'"}, new String[]{"'1'"},
                        0, true));
    }

    /**
     * A one-column key that differs is a single scalar range, closed for a final
     * chunk.
     */
    @Test
    void rendersASingleColumnRange() {
        assertArrayEquals(new String[]{"t.c1 >= '1' AND t.c1 < '5'"},
                boxes(new String[]{"t.c1"}, new String[]{"'1'"}, new String[]{"'5'"},
                        1, false));
        assertArrayEquals(new String[]{"t.c1 >= '1' AND t.c1 <= '5'"},
                boxes(new String[]{"t.c1"}, new String[]{"'1'"}, new String[]{"'5'"},
                        1, true));
    }

    /**
     * When only the last column differs, the fixed prefix is an equality and the
     * suffix is one scalar range.
     */
    @Test
    void rendersALastColumnDifference() {
        assertArrayEquals(new String[]{
                        "t.c1 = '1' AND t.c2 >= '2' AND t.c2 < '3'"},
                boxes(new String[]{"t.c1", "t.c2"},
                        new String[]{"'1'", "'2'"}, new String[]{"'1'", "'3'"},
                        2, false));
        assertArrayEquals(new String[]{
                        "t.c1 = '1' AND t.c2 >= '2' AND t.c2 <= '3'"},
                boxes(new String[]{"t.c1", "t.c2"},
                        new String[]{"'1'", "'2'"}, new String[]{"'1'", "'3'"},
                        2, true));
    }

    /**
     * When the first of two columns differs, the lower tail, middle and upper
     * tail are three boxes.
     */
    @Test
    void rendersTheThreeBoxesForAFirstColumnDifference() {
        assertArrayEquals(new String[]{
                        "t.c1 = '1' AND t.c2 >= '5'",
                        "t.c1 > '1' AND t.c1 < '2'",
                        "t.c1 = '2' AND t.c2 < '6'"},
                boxes(new String[]{"t.c1", "t.c2"},
                        new String[]{"'1'", "'5'"}, new String[]{"'2'", "'6'"},
                        1, false));
    }

    /**
     * A three-column key that differs at the middle column keeps the leading
     * equality in every box.
     */
    @Test
    void rendersTheThreeBoxesForAMiddleColumnDifference() {
        assertArrayEquals(new String[]{
                        "t.a = '1' AND t.b = '2' AND t.c >= '3'",
                        "t.a = '1' AND t.b > '2' AND t.b < '4'",
                        "t.a = '1' AND t.b = '4' AND t.c < '5'"},
                boxes(new String[]{"t.a", "t.b", "t.c"},
                        new String[]{"'1'", "'2'", "'3'"},
                        new String[]{"'1'", "'4'", "'5'"},
                        2, false));
    }

    /**
     * When the first of three columns differs, the lower tail and the upper tail
     * each have one box per suffix column, plus the middle: five boxes. The
     * final flag only affects the last column of the final upper-tail box.
     */
    @Test
    void rendersFiveBoxesForAFirstColumnDifferenceOnThreeColumns() {
        assertArrayEquals(new String[]{
                        "t.a = '1' AND t.b > '2'",
                        "t.a = '1' AND t.b = '2' AND t.c >= '3'",
                        "t.a > '1' AND t.a < '2'",
                        "t.a = '2' AND t.b < '5'",
                        "t.a = '2' AND t.b = '5' AND t.c < '6'"},
                boxes(new String[]{"t.a", "t.b", "t.c"},
                        new String[]{"'1'", "'2'", "'3'"},
                        new String[]{"'2'", "'5'", "'6'"},
                        1, false));
        assertArrayEquals(new String[]{
                        "t.a = '1' AND t.b > '2'",
                        "t.a = '1' AND t.b = '2' AND t.c >= '3'",
                        "t.a > '1' AND t.a < '2'",
                        "t.a = '2' AND t.b < '5'",
                        "t.a = '2' AND t.b = '5' AND t.c <= '6'"},
                boxes(new String[]{"t.a", "t.b", "t.c"},
                        new String[]{"'1'", "'2'", "'3'"},
                        new String[]{"'2'", "'5'", "'6'"},
                        1, true));
    }

    /**
     * A four-column key that differs at the first column renders the three lower
     * tail boxes, the middle box and the three upper tail boxes: 2 * (4 - 1) + 1
     * boxes in all.
     */
    @Test
    void rendersTheSevenBoxesForAFirstColumnDifferenceOfAFourColumnKey() {
        assertArrayEquals(new String[]{
                        "t.a = '1' AND t.b > '2'",
                        "t.a = '1' AND t.b = '2' AND t.c > '3'",
                        "t.a = '1' AND t.b = '2' AND t.c = '3' AND t.d >= '4'",
                        "t.a > '1' AND t.a < '5'",
                        "t.a = '5' AND t.b < '6'",
                        "t.a = '5' AND t.b = '6' AND t.c < '7'",
                        "t.a = '5' AND t.b = '6' AND t.c = '7' AND t.d <= '8'"},
                boxes(new String[]{"t.a", "t.b", "t.c", "t.d"},
                        new String[]{"'1'", "'2'", "'3'", "'4'"},
                        new String[]{"'5'", "'6'", "'7'", "'8'"},
                        1, true));
    }

    /**
     * The same three-box shape with the final flag set only makes the upper
     * tail's last-column bound inclusive (the middle box stays a strict open
     * range), so the last chunk includes its end key.
     */
    @Test
    void rendersInclusiveThreeBoxesForAFinalChunk() {
        assertArrayEquals(new String[]{
                        "t.a = '1' AND t.b = '2' AND t.c >= '3'",
                        "t.a = '1' AND t.b > '2' AND t.b < '4'",
                        "t.a = '1' AND t.b = '4' AND t.c <= '5'"},
                boxes(new String[]{"t.a", "t.b", "t.c"},
                        new String[]{"'1'", "'2'", "'3'"},
                        new String[]{"'1'", "'4'", "'5'"},
                        2, true));
    }

    /**
     * Malformed arrays are rejected with {@code 22023} instead of rendering a
     * degenerate box: a NULL element, mismatched lengths, a difference position
     * outside the key, a blank column name and an empty key.
     */
    @Test
    void rejectsMalformedRangeInputs() {
        assertSqlState("22023", () -> boxes(new String[]{"t.c1"}, new String[]{null},
                new String[]{"'5'"}, 1, false));
        assertSqlState("22023", () -> boxes(new String[]{"t.c1"}, new String[]{"'1'"},
                new String[]{"'5'", "'6'"}, 1, false));
        assertSqlState("22023", () -> boxes(new String[]{"t.c1"}, new String[]{"'1'"},
                new String[]{"'5'"}, 2, false));
        assertSqlState("22023", () -> boxes(new String[]{"  "}, new String[]{"'1'"},
                new String[]{"'5'"}, 1, false));
        assertSqlState("22023", () -> boxes(new String[0], new String[0],
                new String[0], 0, false));
    }

    /**
     * A four-column key that differs only at the last column is one scalar range
     * after a three-column equality prefix.
     */
    @Test
    void rendersALastColumnDifferenceOfAFourColumnKey() {
        assertArrayEquals(new String[]{
                        "t.a = '1' AND t.b = '2' AND t.c = '3' AND t.d >= '4' AND t.d < '9'"},
                boxes(new String[]{"t.a", "t.b", "t.c", "t.d"},
                        new String[]{"'1'", "'2'", "'3'", "'4'"},
                        new String[]{"'1'", "'2'", "'3'", "'9'"},
                        4, false));
    }

    /**
     * A NULL final flag is rejected with {@code 22023}.
     */
    @Test
    void rejectsANullFinalFlag() {
        assertDomainViolation(() -> boxes(new String[]{"t.c1"},
                new String[]{"'1'"}, new String[]{"'5'"}, 1, null));
    }
}
