CREATE OR REPLACE FUNCTION dml_utils_lib.assert_single_statement(
    i_sql_text dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    IMMUTABLE
    SECURITY INVOKER
AS
$function$
DECLARE
    l_characters    text[];
    l_length        integer;
    l_position      integer;
    l_character     text;
    l_next          text;
    l_state         text    := 'code';
    l_block_depth   integer := 0;
    l_dollar_tag    text;
    l_tag_position  integer;
    l_tag_character text;
    l_tag_end       integer;
    l_tag_valid     boolean;
    l_code          integer;
    l_previous_code integer;
    l_escape_quote  boolean := false;
    l_terminated    boolean := false;
    l_has_statement boolean := false;
BEGIN
    -- A lexical scan, not a parse: the routine answers "does this text contain
    -- more than one statement?" without running or planning anything. A
    -- semicolon only separates statements outside a quoted literal, a quoted
    -- identifier, a dollar-quoted body or a comment; a terminator followed by
    -- nothing but whitespace and comments is still one statement, which is what
    -- a trailing semicolon is.
    --
    -- Split into characters once so the loop indexes the array instead of
    -- rescanning the string twice per character. The tag scan stops at the
    -- first character that cannot be part of a tag, so the whole scan stays
    -- linear in the statement length.
    l_characters := pg_catalog.string_to_array(i_sql_text, NULL);
    l_length := coalesce(pg_catalog.array_length(l_characters, 1), 0);
    l_position := 1;

    -- The scan uses standard_conforming_strings = on semantics (only E'...'
    -- literals process backslash escapes), which is the server default.
    -- explain_query_plan pins that setting, so the guard and the EXECUTE it
    -- protects always agree; a direct caller with the setting off gets
    -- fail-closed behaviour (some valid literals are rejected), never a bypass.

    WHILE l_position <= l_length
        LOOP
            l_character := l_characters[l_position];
            l_next := CASE
                          WHEN l_position < l_length THEN l_characters[l_position + 1]
                END;

            IF l_state = 'code' THEN
                IF l_character = ';' THEN
                    IF l_terminated THEN
                        RAISE EXCEPTION 'i_sql_text must contain exactly one statement'
                            USING ERRCODE = '22023',
                                HINT = 'the first statement ends at the terminator; remove the text after it';
                    END IF;
                    l_terminated := true;
                ELSIF l_character = '-' AND l_next = '-' THEN
                    l_state := 'line_comment';
                    l_position := l_position + 1;
                ELSIF l_character = '/' AND l_next = '*' THEN
                    l_state := 'block_comment';
                    l_block_depth := 1;
                    l_position := l_position + 1;
                ELSIF l_terminated
                    AND l_character NOT IN (E' ', E'\t', E'\n', E'\r', E'\f', E'\v')
                THEN
                    RAISE EXCEPTION 'i_sql_text must contain exactly one statement'
                        USING ERRCODE = '22023',
                            HINT = 'only whitespace or comments may follow the statement terminator';
                ELSIF l_character NOT IN (E' ', E'\t', E'\n', E'\r', E'\f', E'\v') THEN
                    l_has_statement := true;

                    IF l_character = '''' THEN
                        l_state := 'single_quote';
                        -- Only an E'...' literal uses backslash escapes under
                        -- standard_conforming_strings = on. The E prefix must
                        -- not continue an identifier.
                        l_previous_code := CASE
                                               WHEN l_position > 2 THEN pg_catalog.ascii(
                                                       l_characters[l_position - 2])
                            END;
                        l_escape_quote := l_position > 1
                            AND l_characters[l_position - 1] IN ('e', 'E')
                            AND (l_position = 2
                                OR NOT ((l_previous_code BETWEEN 48 AND 57)
                                    OR (l_previous_code BETWEEN 65 AND 90)
                                    OR (l_previous_code BETWEEN 97 AND 122)
                                    OR l_previous_code IN (36, 95)
                                    OR l_previous_code >= 128));
                    ELSIF l_character = '"' THEN
                        l_state := 'double_quote';
                    ELSIF l_character = '$' THEN
                        -- A dollar-quoted body starts where a token can start. A
                        -- '$' that continues an identifier (PostgreSQL identifiers
                        -- may contain one, so a$b$ is a single identifier) and a
                        -- positional parameter are ordinary text, not a tag opener.
                        l_previous_code := CASE
                                               WHEN l_position > 1 THEN pg_catalog.ascii(
                                                       l_characters[l_position - 1])
                            END;
                        IF l_position = 1
                            OR NOT ((l_previous_code BETWEEN 48 AND 57)
                                OR (l_previous_code BETWEEN 65 AND 90)
                                OR (l_previous_code BETWEEN 97 AND 122)
                                OR l_previous_code IN (36, 95)
                                OR l_previous_code >= 128)
                        THEN
                            -- Only a tag PostgreSQL accepts opens a body: an
                            -- optional dolq_start character, then dolq_cont
                            -- characters, then '$'. PostgreSQL's lexer takes any
                            -- high-bit character, not just ASCII letters, so the
                            -- check uses code points instead of locale-dependent
                            -- POSIX classes.
                            l_tag_position := l_position + 1;
                            l_tag_end := NULL;
                            l_tag_valid := true;
                            WHILE l_tag_position <= l_length
                                LOOP
                                    l_tag_character := l_characters[l_tag_position];
                                    IF l_tag_character = '$' THEN
                                        l_tag_end := l_tag_position;
                                        EXIT;
                                    END IF;
                                    l_code := pg_catalog.ascii(l_tag_character);
                                    IF l_tag_position = l_position + 1 THEN
                                        l_tag_valid :=
                                                (l_code BETWEEN 65 AND 90)
                                                    OR (l_code BETWEEN 97 AND 122)
                                                    OR l_code = 95
                                                    OR l_code >= 128;
                                    ELSE
                                        l_tag_valid :=
                                                (l_code BETWEEN 48 AND 57)
                                                    OR (l_code BETWEEN 65 AND 90)
                                                    OR (l_code BETWEEN 97 AND 122)
                                                    OR l_code = 95
                                                    OR l_code >= 128;
                                    END IF;
                                    EXIT WHEN NOT l_tag_valid;
                                    l_tag_position := l_tag_position + 1;
                                END LOOP;

                            IF l_tag_valid AND l_tag_end IS NOT NULL THEN
                                l_dollar_tag := pg_catalog.substr(
                                        i_sql_text, l_position, l_tag_end - l_position + 1);
                                l_state := 'dollar_quote';
                                l_position := l_tag_end;
                            END IF;
                        END IF;
                    END IF;
                END IF;
            ELSIF l_state = 'single_quote' THEN
                IF l_character = E'\\' AND l_escape_quote THEN
                    l_position := l_position + 1;
                ELSIF l_character = '''' THEN
                    IF l_next = '''' THEN
                        l_position := l_position + 1;
                    ELSE
                        l_state := 'code';
                    END IF;
                END IF;
            ELSIF l_state = 'double_quote' THEN
                IF l_character = '"' THEN
                    IF l_next = '"' THEN
                        l_position := l_position + 1;
                    ELSE
                        l_state := 'code';
                    END IF;
                END IF;
            ELSIF l_state = 'dollar_quote' THEN
                IF pg_catalog.substr(i_sql_text, l_position,
                                     pg_catalog.length(l_dollar_tag)) = l_dollar_tag
                THEN
                    l_position := l_position + pg_catalog.length(l_dollar_tag) - 1;
                    l_state := 'code';
                END IF;
            ELSIF l_state = 'line_comment' THEN
                -- PostgreSQL ends a line comment at LF or CR.
                IF l_character IN (E'\n', E'\r') THEN
                    l_state := 'code';
                END IF;
            ELSIF l_state = 'block_comment' THEN
                IF l_character = '/' AND l_next = '*' THEN
                    l_block_depth := l_block_depth + 1;
                    l_position := l_position + 1;
                ELSIF l_character = '*' AND l_next = '/' THEN
                    l_block_depth := l_block_depth - 1;
                    l_position := l_position + 1;
                    IF l_block_depth = 0 THEN
                        l_state := 'code';
                    END IF;
                END IF;
            END IF;

            l_position := l_position + 1;
        END LOOP;

    -- No statement at all is a caller error here, not a job for the server: a
    -- bare ';', or only comments and whitespace, would otherwise reach EXPLAIN
    -- and fail with an opaque syntax error.
    IF NOT l_has_statement THEN
        RAISE EXCEPTION 'i_sql_text must contain one statement'
            USING ERRCODE = '22023',
                HINT = 'the text holds no statement (it is only whitespace, comments or terminators)';
    END IF;

    -- Text that ends inside a literal, quoted identifier, dollar-quoted body or
    -- block comment is not a statement this routine can prove singular. Report
    -- it here rather than relying on the server's syntax error, and never treat
    -- an unterminated body as "everything after here is data" (that would fail
    -- open: a phantom tag would hide a following statement). A line comment
    -- needs no newline at the end of the input, so it is a finished construct.
    IF l_state IN ('single_quote', 'double_quote', 'dollar_quote', 'block_comment') THEN
        RAISE EXCEPTION 'i_sql_text ends inside an unterminated %',
            CASE l_state
                WHEN 'single_quote' THEN 'string literal'
                WHEN 'double_quote' THEN 'quoted identifier'
                WHEN 'dollar_quote' THEN 'dollar-quoted body'
                ELSE 'block comment'
                END
            USING ERRCODE = '22023',
                HINT = 'close the construct or remove it before calling the guard';
    END IF;
END;
$function$;

COMMENT ON FUNCTION dml_utils_lib.assert_single_statement IS
    'Raises invalid_parameter_value (22023) unless i_sql_text is exactly one '
        'statement. A semicolon separates statements only outside a quoted '
        'literal, a quoted identifier, a dollar-quoted body or a comment; a '
        'terminator followed by only whitespace and comments is accepted, so a '
        'trailing semicolon is allowed. Also rejects text that contains no '
        'statement and text that ends inside an unterminated literal, quoted '
        'identifier, dollar-quoted body or block comment. Scans with '
        'standard_conforming_strings = on semantics; explain_query_plan pins '
        'that setting so the guard and the EXECUTE it protects agree.';
