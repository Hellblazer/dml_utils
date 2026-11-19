package io.github.eyupmiduck.dmlutils;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits SQL scripts into statements for the tests that execute changelog,
 * rollback and example scripts.
 *
 * <p>The split is lexical: a top-level semicolon ends a statement, while one
 * inside a literal, a quoted identifier, a dollar-quoted body or a comment does
 * not. The handled constructs are:
 *
 * <ul>
 *   <li>single-quoted literals, including doubled quotes ({@code ''});</li>
 *   <li>{@code E'...'} literals, where a backslash escapes the next character
 *       ({@code \'} does not end the string and {@code ''} still does);</li>
 *   <li>double-quoted identifiers, including doubled quotes ({@code ""});</li>
 *   <li>dollar-quoted bodies, whose tag follows PostgreSQL's identifier rule
 *       (a letter or underscore followed by letters, digits or underscores, so
 *       a non-ASCII tag such as {@code $täg$} is valid); {@code $1} is a
 *       parameter, not a tag;</li>
 *   <li>line comments and nested block comments.</li>
 * </ul>
 *
 * <p>A plain {@code '...'} literal is scanned as if
 * {@code standard_conforming_strings} were {@code on} (the server default), so a
 * backslash in it is an ordinary character; only the {@code E} prefix enables
 * escapes.
 *
 * <p>Whitespace-only fragments are dropped and a trailing statement without a
 * semicolon is kept.
 */
final class SqlStatements {

    private SqlStatements() {
    }

    /**
     * Splits a script into its statements, in order.
     *
     * @param script the SQL script
     * @return the statements without their terminating semicolons
     * @throws IllegalArgumentException if a dollar quote is not terminated
     */
    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                int end = script.indexOf('\n', i);
                if (end < 0) {
                    end = script.length();
                }
                current.append(script, i, end);
                i = end;
            } else if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                // Block comments nest, and a semicolon inside one must not split.
                int depth = 0;
                int end = i;
                while (end < script.length()) {
                    if (script.startsWith("/*", end)) {
                        depth++;
                        end += 2;
                    } else if (script.startsWith("*/", end)) {
                        depth--;
                        end += 2;
                        if (depth == 0) {
                            break;
                        }
                    } else {
                        end++;
                    }
                }
                current.append(script, i, end);
                i = end;
            } else if (c == '\'') {
                boolean escapeString = isEscapeString(script, i);
                current.append(c);
                i++;
                while (i < script.length()) {
                    char quoted = script.charAt(i);
                    current.append(quoted);
                    i++;
                    if (escapeString && quoted == '\\' && i < script.length()) {
                        // An E'' escape protects the next character, including a
                        // quote, and the doubled-quote form still works.
                        current.append(script.charAt(i));
                        i++;
                    } else if (quoted == '\'' && i < script.length() && script.charAt(i) == '\'') {
                        current.append('\'');
                        i++;
                    } else if (quoted == '\'') {
                        break;
                    }
                }
            } else if (c == '"') {
                current.append(c);
                i++;
                while (i < script.length()) {
                    char quoted = script.charAt(i);
                    current.append(quoted);
                    i++;
                    if (quoted == '"' && i < script.length() && script.charAt(i) == '"') {
                        current.append('"');
                        i++;
                    } else if (quoted == '"') {
                        break;
                    }
                }
            } else if (c == '$' && isDollarQuoteStart(script, i)) {
                int tagEnd = script.indexOf('$', i + 1);
                String tag = script.substring(i, tagEnd + 1);
                int close = script.indexOf(tag, tagEnd + 1);
                if (close < 0) {
                    throw new IllegalArgumentException("unterminated dollar quote " + tag);
                }
                current.append(script, i, close + tag.length());
                i = close + tag.length();
            } else if (c == ';') {
                if (!current.toString().isBlank()) {
                    statements.add(current.toString());
                }
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    /**
     * Whether the single quote at the given index opens an {@code E'...'} escape
     * string rather than a plain literal.
     *
     * <p>The {@code E} prefix must be a separate token prefix, so {@code abcE'} is
     * not treated as an escape string (it is also not valid SQL; the check only
     * avoids misreading an identifier's trailing {@code e}).
     *
     * @param script the script
     * @param quote  the index of the opening {@code '}
     * @return true when the literal is an {@code E'...'} string
     */
    private static boolean isEscapeString(String script, int quote) {
        if (quote < 1) {
            return false;
        }
        char prefix = script.charAt(quote - 1);
        if (prefix != 'E' && prefix != 'e') {
            return false;
        }
        return quote == 1 || !isIdentifierPart(script.charAt(quote - 2));
    }

    /**
     * Whether the character can appear in an unquoted identifier (letters,
     * digits, underscore and dollar).
     *
     * @param c the character
     * @return true for an identifier character
     */
    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Whether the {@code $} at the given index opens a valid dollar-quote tag.
     *
     * <p>The tag follows PostgreSQL's unquoted-identifier rule, so it may contain
     * non-ASCII letters ({@code $täg$}); a tag that starts with a digit is not a
     * quote, which keeps {@code $1} a parameter reference.
     *
     * @param script the script
     * @param start  the index of the opening {@code $}
     * @return true when a dollar quote starts here
     */
    private static boolean isDollarQuoteStart(String script, int start) {
        int tagEnd = script.indexOf('$', start + 1);
        if (tagEnd < 0) {
            return false;
        }
        String tag = script.substring(start + 1, tagEnd);
        if (tag.isEmpty()) {
            return true;
        }
        int first = tag.codePointAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int offset = Character.charCount(first); offset < tag.length(); ) {
            int codePoint = tag.codePointAt(offset);
            if (!Character.isLetterOrDigit(codePoint) && codePoint != '_') {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
