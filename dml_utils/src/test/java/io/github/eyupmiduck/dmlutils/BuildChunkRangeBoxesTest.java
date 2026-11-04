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
     * A NULL final flag is rejected with {@code 22023}.
     */
    @Test
    void rejectsANullFinalFlag() {
        assertSqlState("22023", () -> boxes(new String[]{"t.c1"},
                new String[]{"'1'"}, new String[]{"'5'"}, 1, null));
    }
}
