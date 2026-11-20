CREATE OR REPLACE FUNCTION dml_utils_lib.build_key_render_columns(
    i_table_alias dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_key_columns dml_utils_data.non_null_element_name_array,
    i_key_kinds dml_utils_data.non_null_element_text_array,
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
    l_column_count integer;
    l_kind         text;
    l_collation    text;
BEGIN
    -- The key columns and kinds must be one-dimensional, 1-based and the same
    -- non-empty length. A NULL or empty columns array makes array_length NULL,
    -- which the length comparison below rejects.
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

    -- The alias is concatenated into every returned column name and then
    -- substituted by the per-chunk renderer, so screen it for the reserved
    -- sentinels like the column names.
    IF pg_catalog.strpos(i_table_alias, pg_catalog.chr(1)) > 0
        OR pg_catalog.strpos(i_table_alias, pg_catalog.chr(2)) > 0
    THEN
        RAISE EXCEPTION 'table alias contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    l_column_count := pg_catalog.array_length(i_key_columns, 1);
    o_column_names := ARRAY []::text[];
    o_key_collations := ARRAY []::text[];
    FOR l_position IN 1..l_column_count
        LOOP
            l_kind := i_key_kinds[l_position];

            -- The argument domain rejects a NULL element.
            IF l_kind NOT IN ('bigint', 'text', 'uuid', 'timestamptz') THEN
                RAISE EXCEPTION 'unsupported key kind % at position %', l_kind, l_position
                    USING ERRCODE = '22023';
            END IF;

            -- The argument domain rejects NULL elements; an empty or
            -- whitespace-only name would still render a degenerate %I token and
            -- fail later when a worker parses the chunk SQL. Trim the same
            -- whitespace set the dml_utils_data.non_null_text domain uses.
            IF pg_catalog.btrim(i_key_columns[l_position]::text, E' \t\n\r\f\013') = ''
            THEN
                RAISE EXCEPTION 'key column name must not be blank at position %',
                    l_position
                    USING ERRCODE = '22023';
            END IF;

            -- The prepared template uses chr(1)/chr(2) as token sentinels; a column
            -- name carrying one would be rewritten as a token during rendering.
            IF pg_catalog.strpos(i_key_columns[l_position]::text, pg_catalog.chr(1)) > 0
                OR pg_catalog.strpos(i_key_columns[l_position]::text, pg_catalog.chr(2)) > 0
            THEN
                RAISE EXCEPTION 'key column name contains a reserved substitution character at position %',
                    l_position
                    USING ERRCODE = '22023';
            END IF;

            o_column_names := o_column_names || pg_catalog.format('%I.%I',
                                                                  i_table_alias,
                                                                  i_key_columns[l_position]);

            -- Precompute the text column's collation once (the other kinds do not
            -- order by collation). NULL means the column is not in the catalog (a
            -- renderer unit test with a synthetic table), so
            -- render_prepared_chunk_sql falls back to the default operator. The
            -- name is schema-qualified: the consumer interpolates it into a
            -- COLLATE clause executed later, where an unqualified name would
            -- resolve against the executing session's search_path and could bind a
            -- different collation (or none).
            IF l_kind = 'text' THEN
                l_collation := NULL;
                -- A text column is collatable, so attcollation is never 0; a row
                -- with no joined collation (the column is absent, or a synthetic
                -- table in a renderer unit test) leaves l_collation NULL, which
                -- the consumer reads as "use the default operator".
                SELECT CASE
                           WHEN c.oid IS NULL THEN NULL
                           ELSE pg_catalog.format('%I.%I', cn.nspname, c.collname)
                           END
                INTO l_collation
                FROM pg_catalog.pg_attribute AS a
                         LEFT JOIN pg_catalog.pg_collation AS c
                                   ON c.oid = a.attcollation
                         LEFT JOIN pg_catalog.pg_namespace AS cn
                                   ON cn.oid = c.collnamespace
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

    -- A key with the same column twice cannot be ordered consistently.
    IF (SELECT pg_catalog.count(*)
        FROM pg_catalog.unnest(i_key_columns) AS c(column_name))
        <> (SELECT pg_catalog.count(DISTINCT c.column_name)
            FROM pg_catalog.unnest(i_key_columns) AS c(column_name))
    THEN
        RAISE EXCEPTION 'key columns must be distinct'
            USING ERRCODE = '22023';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.build_key_render_columns IS
    'Validates a chunk render key and returns the alias-qualified quoted column '
        'names plus the schema-qualified collation to use for each text column (NULL '
        'when the column is not in the catalog). Shared by prepare_chunk_sql_render '
        'so the render context is built once per run.';
