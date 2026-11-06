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

    private static final Pattern SELECT_STAR =
            Pattern.compile("(?i)\\bselect\\s+(?:[a-z_][a-z0-9_]*\\.)?\\*");

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
}
