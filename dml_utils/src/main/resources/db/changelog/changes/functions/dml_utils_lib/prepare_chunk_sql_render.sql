CREATE OR REPLACE FUNCTION dml_utils_lib.prepare_chunk_sql_render(
    i_sql_text dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_key_columns name[],
    i_key_kinds text[],
    OUT o_template text,
    OUT o_column_names text[],
    OUT o_key_collations text[]
)
    RETURNS record
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    -- Control-character sentinels that stand in for one token each. The template's
    -- <driving_table> token is expanded here; <chunking_clause> is left as a
    -- sentinel for render_prepared_chunk_sql to replace per chunk, so neither
    -- substitution can be re-scanned by the other.
    l_driving_table_sentinel   constant text := pg_catalog.chr(1);
    l_chunking_clause_sentinel constant text := pg_catalog.chr(2);
    l_driving_table                     text;
    l_column_count                      integer;
    l_kind                              text;
    l_collation                         text;
BEGIN
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- The key columns and kinds must be one-dimensional, 1-based and the same
    -- non-empty length. The per-chunk boundary arrays are checked against this
    -- length in render_prepared_chunk_sql. A NULL or empty columns array makes
    -- array_length NULL, which the length comparison below rejects.
    IF pg_catalog.array_ndims(i_key_columns) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_columns, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_key_kinds) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_kinds, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_length(i_key_columns, 1) IS NULL
        OR pg_catalog.array_length(i_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_key_kinds, 1)
    THEN
        RAISE EXCEPTION 'key columns and kinds must be one-dimensional, 1-based, same-length and non-empty'
            USING ERRCODE = '22023';
    END IF;

    l_column_count := pg_catalog.array_length(i_key_columns, 1);
    o_column_names := ARRAY []::text[];
    o_key_collations := ARRAY []::text[];
    FOR l_position IN 1..l_column_count
        LOOP
            l_kind := i_key_kinds[l_position];

            -- A NULL kind would slip past NOT IN (NULL NOT IN (...) is NULL,
            -- not true), so guard it explicitly.
            IF l_kind IS NULL OR l_kind NOT IN ('bigint', 'text', 'uuid', 'timestamptz') THEN
                RAISE EXCEPTION 'unsupported key kind %', l_kind
                    USING ERRCODE = '22023';
            END IF;

            -- A NULL or empty column name would render a degenerate %I token and
            -- only fail later when a worker parses the chunk SQL; reject it here.
            IF i_key_columns[l_position] IS NULL
                OR i_key_columns[l_position] = ''
            THEN
                RAISE EXCEPTION 'key column name must not be NULL or empty at position %',
                    l_position
                    USING ERRCODE = '22023';
            END IF;

            o_column_names := o_column_names || pg_catalog.format('%I.%I',
                                                                  i_table_alias,
                                                                  i_key_columns[l_position]);

            -- Precompute the text column's collation once (the other kinds do not
            -- order by collation). NULL means the column is not in the catalog (a
            -- renderer unit test with a synthetic table), so
            -- render_prepared_chunk_sql falls back to the default operator;
            -- 'default' is the default collation; any other value is the quoted
            -- collation name.
            IF l_kind = 'text' THEN
                l_collation := NULL;
                SELECT CASE
                           WHEN a.attcollation = 0 THEN 'default'
                           ELSE pg_catalog.quote_ident(c.collname)
                           END
                INTO l_collation
                FROM pg_catalog.pg_attribute AS a
                         LEFT JOIN pg_catalog.pg_collation AS c
                                   ON c.oid = a.attcollation
                WHERE a.attrelid = pg_catalog.to_regclass(
                        pg_catalog.format('%I.%I', i_schema_name, i_table_name))
                  AND a.attname = i_key_columns[l_position];
                -- array_append, not `||`: concatenating a NULL element keeps the
                -- array aligned by position (a plain `array || NULL` would not add
                -- an element).
                o_key_collations := pg_catalog.array_append(o_key_collations, l_collation);
            ELSE
                o_key_collations := pg_catalog.array_append(o_key_collations, NULL);
            END IF;
        END LOOP;

    -- The driving table is referenced as "<schema>.<table> <alias>" so the
    -- template's column references can use the alias.
    l_driving_table := pg_catalog.format('%I.%I %I',
                                         i_schema_name, i_table_name, i_table_alias);

    -- A quoted identifier or the caller's template could in principle contain a
    -- sentinel character; reject that so the substitution below stays unambiguous.
    IF pg_catalog.strpos(l_driving_table, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_driving_table, l_chunking_clause_sentinel) > 0
        OR pg_catalog.strpos(i_sql_text, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(i_sql_text, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'template or identifier contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    -- Replace both tokens with sentinels first, then expand the driving-table
    -- sentinel. The chunking-clause sentinel is left in place for the per-chunk
    -- render; the substituted driving table is inserted last and is never
    -- re-scanned, so a value that literally contains <chunking_clause> cannot
    -- mangle the output.
    o_template := pg_catalog.replace(i_sql_text, '<driving_table>', l_driving_table_sentinel);
    o_template := pg_catalog.replace(o_template, '<chunking_clause>', l_chunking_clause_sentinel);
    o_template := pg_catalog.replace(o_template, l_driving_table_sentinel, l_driving_table);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.prepare_chunk_sql_render IS
    'Validates a chunking template and its key columns and kinds once, and '
        'returns the run-invariant render context: the template with <driving_table> '
        'already substituted and <chunking_clause> replaced by an internal sentinel, '
        'the alias-qualified quoted column names, and each text column''s collation '
        '(NULL when the column is not in the catalog). The caller must pass the '
        'kind resolved from the catalog for each column; the public wrappers derive '
        'the columns and kinds together, so a direct lib caller is responsible for '
        'keeping them consistent. render_prepared_chunk_sql consumes the context per '
        'chunk, so the per-chunk call only builds the range predicate and substitutes '
        'the chunking clause.';
