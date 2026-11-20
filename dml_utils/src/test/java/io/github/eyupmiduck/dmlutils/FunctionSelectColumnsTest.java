package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every SQL routine lists its selected columns explicitly instead
 * of using {@code SELECT *}, {@code alias.*} or {@code table.*}. An explicit
 * list keeps a routine's result and {@code INTO} assignment stable when a table
 * gains or reorders columns.
 */
class FunctionSelectColumnsTest {

    private static final Pattern SELECT_STAR = Pattern.compile(
            "(?i)\\bselect\\s+(?:(?:distinct|all)\\s+"
                    + "(?:on\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*)?)?"
                    + "(?:(?:[a-z_][a-z0-9_]*\\.)+)?\\*");

    /**
     * A star that is not the first select item ({@code SELECT id, t.*}), which
     * the leading-star pattern above cannot see. A comma plus qualifiers and a
     * star, followed by another item or FROM, cannot be {@code count(*)} or a
     * multiplication.
     */
    private static final Pattern STAR_SELECT_ITEM = Pattern.compile(
            "(?i),\\s*(?:[a-z_][a-z0-9_]*\\.)*\\*(?:\\s*,|\\s+from\\b)");

    /**
     * Removes SQL comments before matching, so a comment that mentions
     * {@code SELECT *} is not reported as a routine using one.
     *
     * @param sql the SQL text
     * @return the text without line and block comments
     */
    private static String withoutComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ");
    }

    /**
     * Whether the SQL contains a star select in executable text.
     *
     * @param sql the SQL text
     * @return true when a star select is found outside comments
     */
    private static boolean hasStarSelect(String sql) {
        String code = withoutComments(sql);
        return SELECT_STAR.matcher(code).find() || STAR_SELECT_ITEM.matcher(code).find();
    }

    /**
     * The pattern detects a star select even behind a {@code DISTINCT ON (...)}
     * modifier.
     */
    @Test
    void detectsDistinctOnStarSelects() {
        assertTrue(SELECT_STAR.matcher(
                                "SELECT DISTINCT ON (id) * FROM dml_utils_data.migration_run")
                        .find(),
                "a DISTINCT ON star select must be detected");
        assertTrue(SELECT_STAR.matcher("SELECT DISTINCT t.* FROM dml_utils_data.migration_run t")
                .find(), "a DISTINCT qualified star select must be detected");
        assertTrue(SELECT_STAR.matcher(
                        "SELECT DISTINCT ON (lower(id)) * FROM dml_utils_data.migration_run")
                .find(), "a DISTINCT ON expression with parentheses must be detected");
        assertTrue(SELECT_STAR.matcher("SELECT DISTINCT ON (id)* FROM dml_utils_data.migration_run")
                .find(), "DISTINCT ON without whitespace before the star must be detected");
    }

    /**
     * No routine under the changelog functions directory contains a star select.
     *
     * @throws IOException if the function SQL resources cannot be read
     */
    @Test
    void functionsListSelectedColumnsExplicitly() throws IOException {
        List<String> files = TestResources.readSqlFiles("db/changelog/changes/functions");
        List<String> offenders = files.stream()
                .filter(FunctionSelectColumnsTest::hasStarSelect)
                .toList();

        assertTrue(offenders.isEmpty(),
                () -> "SQL routines must list columns explicitly; found SELECT * / alias.* in:\n"
                        + String.join("\n---\n", offenders));
    }

    /**
     * A star after another select item is detected, while an aggregate or an
     * ordinary column list is not.
     */
    @Test
    void detectsAStarThatIsNotTheFirstSelectItem() {
        assertTrue(STAR_SELECT_ITEM.matcher("SELECT id, t.* FROM t").find());
        assertTrue(STAR_SELECT_ITEM.matcher("SELECT id, * FROM t").find());
        assertFalse(STAR_SELECT_ITEM.matcher("SELECT count(*) FROM t").find());
        assertFalse(STAR_SELECT_ITEM.matcher("SELECT id, payload FROM t").find());
    }

    /**
     * A star mentioned in a comment is not a star select in the routine.
     */
    @Test
    void ignoresStarSelectsInComments() {
        assertFalse(hasStarSelect("-- never write SELECT * FROM t\nSELECT id FROM t"),
                "a line comment mentioning a star select is not an offender");
        assertFalse(hasStarSelect("/* SELECT t.* FROM t */\nSELECT id FROM t"),
                "a block comment mentioning a star select is not an offender");
        assertTrue(hasStarSelect("SELECT * FROM t -- a comment"),
                "a real star select is still found");
    }

    /**
     * The pattern catches the shapes a routine can use: a bare star, the
     * DISTINCT/ALL modifiers, and one or more qualifier levels (alias or
     * schema.table). An aggregate such as {@code count(*)} is not a star select.
     */
    @Test
    void detectsEveryStarSelectShape() {
        for (String statement : List.of(
                "SELECT * FROM t",
                "select distinct * from t",
                "SELECT ALL t.* FROM t",
                "SELECT schema.table.* FROM schema.table",
                "SELECT a.b.* FROM a.b")) {
            assertTrue(SELECT_STAR.matcher(statement).find(),
                    "should detect a star select in: " + statement);
        }
        for (String statement : List.of(
                "SELECT count(*) FROM t",
                "SELECT id, payload FROM t")) {
            assertFalse(SELECT_STAR.matcher(statement).find(), "should not detect a star select in: " + statement);
        }
    }
}
