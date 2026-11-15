package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every SQL routine lists its selected columns explicitly instead
 * of using {@code SELECT *}, {@code alias.*} or {@code table.*}. An explicit
 * list keeps a routine's result and {@code INTO} assignment stable when a table
 * gains or reorders columns.
 */
class FunctionSelectColumnsTest {

    private static final Pattern SELECT_STAR = Pattern.compile(
            "(?i)\\bselect\\s+(?:(?:distinct|all)\\s+)?(?:(?:[a-z_][a-z0-9_]*\\.)+)?\\*");

    /**
     * No routine under the changelog functions directory contains a star select.
     *
     * @throws IOException if the function SQL resources cannot be read
     */
    @Test
    void functionsListSelectedColumnsExplicitly() throws IOException {
        List<String> files = TestResources.readSqlFiles("db/changelog/changes/functions");
        List<String> offenders = files.stream()
                .filter(sql -> SELECT_STAR.matcher(sql).find())
                .toList();

        assertTrue(offenders.isEmpty(),
                () -> "SQL routines must list columns explicitly; found SELECT * / alias.* in:\n"
                        + String.join("\n---\n", offenders));
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
            assertTrue(!SELECT_STAR.matcher(statement).find(),
                    "should not detect a star select in: " + statement);
        }
    }
}
