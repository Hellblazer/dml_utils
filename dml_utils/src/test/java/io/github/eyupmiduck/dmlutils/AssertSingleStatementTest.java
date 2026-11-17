package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_single_statement}: a single statement is
 * accepted, semicolons inside literals, identifiers, dollar-quoted bodies and
 * comments do not separate statements, and a second statement raises
 * {@code 22023}.
 */
class AssertSingleStatementTest extends PostgresTestBase {

    private void accepts(String sql) {
        assertDoesNotThrow(() -> Routines.assertSingleStatement(dsl.configuration(), sql));
    }

    private void rejects(String sql) {
        assertSqlState("22023", () -> Routines.assertSingleStatement(
                dsl.configuration(), sql));
    }

    /**
     * A bare statement, with or without a trailing terminator, is accepted.
     */
    @Test
    void acceptsASingleStatement() {
        accepts("SELECT 1");
        accepts("SELECT 1;");
        accepts("SELECT 1 ; ");
    }

    /**
     * Only whitespace and comments may follow a terminator, so a trailing
     * comment is accepted while a second statement is rejected.
     */
    @Test
    void acceptsCommentsAfterTheTerminatorButNotASecondStatement() {
        accepts("SELECT 1; -- done");
        accepts("SELECT 1;\n/* done */\n");
        rejects("SELECT 1; SELECT 2");
        rejects("SELECT 1; -- trailing\nSELECT 2");
        rejects("SELECT 1;;");
    }

    /**
     * A terminator inside a single-quoted literal is data, not a separator. A
     * doubled quote stays inside the literal, and an {@code E'...'} literal
     * treats a backslash-escaped quote as content too.
     */
    @Test
    void acceptsSemicolonsInsideStringLiterals() {
        accepts("SELECT 'a;b'");
        accepts("SELECT 'it''s; fine'");
        accepts("SELECT E'it\\'s; fine'");
        rejects("SELECT 'a;b'; SELECT 2");
    }

    /**
     * A terminator inside a quoted identifier, a line comment, a nested block
     * comment or a dollar-quoted body is not a separator.
     */
    @Test
    void acceptsSemicolonsInsideIdentifiersAndComments() {
        accepts("SELECT 1 AS \"a;b\"");
        accepts("SELECT 1 -- a;b");
        accepts("SELECT 1 /* a;b */");
        accepts("SELECT 1 /* a /* b;c */ d;e */");
        accepts("SELECT $$a;b$$");
        accepts("SELECT $body$a;b$body$");
        accepts("SELECT $body$a;$$;b$body$");
        rejects("SELECT \"a;b\"; SELECT 2");
        rejects("SELECT $$a;b$$; SELECT 2");
    }

    /**
     * A '$' that continues an identifier is not a dollar-quote opener, so the
     * terminator after the identifier is still seen. (PostgreSQL identifiers may
     * contain '$', so {@code a$b$} is one identifier.)
     */
    @Test
    void treatsADollarInsideAnIdentifierAsText() {
        accepts("SELECT 0 AS a$b$");
        rejects("SELECT 0 AS a$b$; SELECT 2");
    }

    /**
     * A non-ASCII dollar-quote tag is recognised, because PostgreSQL's tag lexer
     * accepts any letter, not ASCII only.
     */
    @Test
    void acceptsANonAsciiDollarTag() {
        accepts("SELECT $t\u00e4g$a;b$t\u00e4g$");
    }

    /**
     * Text that holds no statement at all is rejected rather than passed to the
     * server as an opaque syntax error.
     */
    @Test
    void rejectsTextWithoutAStatement() {
        rejects(";");
        rejects("   ;   ");
        rejects("-- only a comment");
        rejects("/* only a comment */");
        rejects("-- c\n/* d */;");
    }

    /**
     * Text that ends inside an unterminated literal, quoted identifier,
     * dollar-quoted body or block comment is rejected: an unclosed body must not
     * be treated as "the rest is data", which would fail open.
     */
    @Test
    void rejectsAnUnterminatedConstruct() {
        rejects("SELECT 'abc");
        rejects("SELECT E'abc\\");
        rejects("SELECT \"a");
        rejects("SELECT $body$a;b");
        rejects("SELECT 1 /* a /* b */");
    }

    /**
     * An unterminated dollar tag is ordinary text, so the scan keeps looking for
     * a real statement separator.
     */
    @Test
    void treatsAnUnterminatedDollarTagAsOrdinaryText() {
        accepts("SELECT 1 FROM $");
        rejects("SELECT $x; SELECT 2");
    }
}
