package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SqlStatements#split(String)}: a top-level semicolon ends a
 * statement, while one inside a literal, a quoted identifier, a dollar-quoted
 * body or a comment does not.
 */
class SqlStatementsTest {

    /**
     * Top-level semicolons split and the terminating semicolons are dropped.
     */
    @Test
    void splitsAtTopLevelSemicolons() {
        assertEquals(List.of("SELECT 1", " SELECT 2"),
                SqlStatements.split("SELECT 1; SELECT 2;"));
    }

    /**
     * A semicolon inside a plain literal does not split, and a trailing
     * statement without a semicolon is kept.
     */
    @Test
    void keepsSemicolonsInsidePlainLiterals() {
        assertEquals(List.of("SELECT 'a;b'", " SELECT 2", " SELECT 3"),
                SqlStatements.split("SELECT 'a;b'; SELECT 2; SELECT 3"));
    }

    /**
     * A backslash in a plain literal is an ordinary character (the server
     * default {@code standard_conforming_strings = on}), so the quote after it
     * closes the string.
     */
    @Test
    void doesNotProcessBackslashEscapesInPlainLiterals() {
        assertEquals(List.of("SELECT 'a\\'", " SELECT 2"),
                SqlStatements.split("SELECT 'a\\'; SELECT 2;"));
    }

    /**
     * An {@code E'...'} literal keeps an escaped quote and the semicolon after
     * it in the same statement.
     */
    @Test
    void keepsEscapedQuotesAndSemicolonsInEStrings() {
        assertEquals(List.of("SELECT E'it\\'s; still one'", " SELECT 2"),
                SqlStatements.split("SELECT E'it\\'s; still one'; SELECT 2;"));
    }

    /**
     * The doubled-quote form still closes an {@code E'...'} literal.
     */
    @Test
    void keepsDoubledQuotesInEStrings() {
        assertEquals(List.of("SELECT E'it''s; still one'", " SELECT 2"),
                SqlStatements.split("SELECT E'it''s; still one'; SELECT 2;"));
    }

    /**
     * A semicolon inside a quoted identifier does not split.
     */
    @Test
    void keepsSemicolonsInsideQuotedIdentifiers() {
        assertEquals(List.of("SELECT 1 AS \"a;b\"", " SELECT 2"),
                SqlStatements.split("SELECT 1 AS \"a;b\"; SELECT 2;"));
    }

    /**
     * A doubled quote inside a quoted identifier does not end it.
     */
    @Test
    void keepsDoubledQuotesInsideQuotedIdentifiers() {
        assertEquals(List.of("SELECT 1 AS \"a\"\";b\""),
                SqlStatements.split("SELECT 1 AS \"a\"\";b\";"));
    }

    /**
     * A dollar-quote tag may contain non-ASCII letters, so the semicolons in its
     * body stay inside the statement.
     */
    @Test
    void keepsSemicolonsInsideNonAsciiDollarQuotes() {
        String script = "CREATE FUNCTION f() RETURNS int LANGUAGE sql AS "
                + "$täg$ SELECT 1; SELECT 2; $täg$; SELECT 3;";

        List<String> statements = SqlStatements.split(script);

        assertEquals(2, statements.size(), "the function body is one statement");
        assertEquals("CREATE FUNCTION f() RETURNS int LANGUAGE sql AS "
                + "$täg$ SELECT 1; SELECT 2; $täg$", statements.get(0));
        assertEquals(" SELECT 3", statements.get(1));
    }

    /**
     * {@code $1} and {@code $2} are positional parameters, not dollar-quote
     * tags, so each statement still splits.
     */
    @Test
    void treatsPositionalParametersAsNotDollarQuotes() {
        assertEquals(List.of("SELECT $1", " SELECT $2"),
                SqlStatements.split("SELECT $1; SELECT $2;"));
    }

    /**
     * Semicolons and quotes inside line and nested block comments do not split.
     */
    @Test
    void ignoresSemicolonsInsideComments() {
        assertEquals(List.of("-- don't; split\nSELECT 1"),
                SqlStatements.split("-- don't; split\nSELECT 1;"));
        assertEquals(List.of("/* a /* b; */ c; */ SELECT 1"),
                SqlStatements.split("/* a /* b; */ c; */ SELECT 1;"));
    }

    /**
     * A whitespace-only script has no statements.
     */
    @Test
    void ignoresWhitespaceOnlyScripts() {
        assertTrue(SqlStatements.split(" \n\t").isEmpty());
    }

    /**
     * An unterminated dollar quote is an error rather than a silent split.
     */
    @Test
    void rejectsAnUnterminatedDollarQuote() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SqlStatements.split("SELECT $täg$ SELECT 1;"));
        assertTrue(error.getMessage().contains("unterminated dollar quote"),
                () -> "unexpected message: " + error.getMessage());
    }
}
