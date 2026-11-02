CREATE OR REPLACE FUNCTION dml_utils_lib.build_boundary_population_sql(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_key_columns name[],
    i_key_kinds text[],
    i_run_id bigint,
    i_chunk_size bigint
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_select_list text;
    l_key_id_list     text;
    l_order_by        text;
    l_order_by_desc   text;
    l_bigint_terms      text[];
    l_text_terms        text[];
    l_uuid_terms        text[];
    l_timestamptz_terms text[];
    l_pack_expression text;
    l_kind            text;
BEGIN
    -- The boundary-key columns and kinds must be one-dimensional, 1-based and
    -- the same non-empty length of one to three. A different lower bound, a
    -- multidimensional array, an empty array or a length mismatch would index
    -- the wrong element or render a degenerate statement.
    IF pg_catalog.array_ndims(i_key_columns) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_columns, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_key_kinds) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_kinds, 1) IS DISTINCT FROM 1
        OR pg_catalog.cardinality(i_key_kinds) NOT BETWEEN 1 AND 3
        OR pg_catalog.cardinality(i_key_columns) IS DISTINCT FROM pg_catalog.cardinality(i_key_kinds)
    THEN
        RAISE EXCEPTION 'boundary-key columns and kinds must be one-dimensional, 1-based and of the same non-empty length of one to three'
            USING ERRCODE = '22023';
    END IF;

    -- The run id and chunk size are inlined (both bigint, so no injection
    -- surface). A NULL run id would render an empty token and a non-positive
    -- chunk size would render a degenerate divisor.
    IF i_run_id IS NULL OR i_chunk_size IS NULL OR i_chunk_size <= 0 THEN
        RAISE EXCEPTION 'run id must not be NULL and chunk size must be positive'
            USING ERRCODE = '22023';
    END IF;

    -- Build, position by position: the SELECT list that aliases each key column
    -- as id1, id2, ..., the matching id list, the ORDER BY, and the pack
    -- expression. All identifiers are catalog names quoted with %I/quote_ident;
    -- every kind comes from the resolver whitelist (primary_key_kinds or
    -- ordered_key_kinds), so interpolating the result is safe.
    l_bigint_terms := pg_catalog.array_fill('NULL'::text, ARRAY [pg_catalog.array_length(i_key_kinds, 1)]);
    l_text_terms := pg_catalog.array_fill('NULL'::text, ARRAY [pg_catalog.array_length(i_key_kinds, 1)]);
    l_uuid_terms := pg_catalog.array_fill('NULL'::text, ARRAY [pg_catalog.array_length(i_key_kinds, 1)]);
    l_timestamptz_terms := pg_catalog.array_fill('NULL'::text, ARRAY [pg_catalog.array_length(i_key_kinds, 1)]);
    l_key_select_list := '';
    l_key_id_list := '';
    l_order_by := '';
    l_order_by_desc := '';
    FOR l_position IN 1..pg_catalog.array_length(i_key_kinds, 1)
        LOOP
            l_kind := i_key_kinds[l_position];

            -- A NULL kind would slip past NOT IN (NULL NOT IN (...) is NULL,
            -- not true) and a NULL or empty column name would render a
            -- degenerate %I token; reject both before building the statement.
            IF l_kind IS NULL OR l_kind NOT IN ('bigint', 'text', 'uuid', 'timestamptz') THEN
                RAISE EXCEPTION 'unsupported key kind %', l_kind
                    USING ERRCODE = '22023';
            END IF;

            IF i_key_columns[l_position] IS NULL
                OR i_key_columns[l_position] = ''
            THEN
                RAISE EXCEPTION 'boundary-key column name must not be NULL or empty at position %',
                    l_position
                    USING ERRCODE = '22023';
            END IF;

            l_key_select_list := l_key_select_list || pg_catalog.format('%s%s AS id%s',
                                                                        CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                        pg_catalog.quote_ident(i_key_columns[l_position]),
                                                                        l_position);
            l_key_id_list := l_key_id_list || pg_catalog.format('%sid%s',
                                                                CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                l_position);
            l_order_by := l_order_by || pg_catalog.format('%s%s',
                                                          CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                          pg_catalog.quote_ident(i_key_columns[l_position]));
            -- DESC applies per ORDER BY term, so the backward scan needs it on
            -- every column (a trailing DESC would leave the earlier columns ASC).
            -- Order by the SELECT's idN aliases, not the raw column names: this
            -- is a top-level ORDER BY over the aliased select, and PostgreSQL
            -- prefers an output alias over an input column, so a key column
            -- literally named id1 at another position would otherwise resolve
            -- to the wrong column. The aliases are the key columns in key order.
            l_order_by_desc := l_order_by_desc || pg_catalog.format('%sid%s DESC',
                                                                    CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                    l_position);

            IF l_kind = 'bigint' THEN
                l_bigint_terms[l_position] := pg_catalog.format('id%s::bigint', l_position);
            ELSIF l_kind = 'text' THEN
                l_text_terms[l_position] := pg_catalog.format('id%s::text', l_position);
            ELSIF l_kind = 'uuid' THEN
                l_uuid_terms[l_position] := pg_catalog.format('id%s::uuid', l_position);
            ELSE
                l_timestamptz_terms[l_position] := pg_catalog.format('id%s::timestamptz', l_position);
            END IF;
        END LOOP;

    l_pack_expression := pg_catalog.format(
            'ROW(%s, %s, %s, %s)::dml_utils_data.migration_key',
            CASE
                WHEN 'bigint' = ANY (i_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::bigint[]',
                                           pg_catalog.array_to_string(l_bigint_terms, ', '))
                ELSE 'NULL::bigint[]'
                END,
            CASE
                WHEN 'text' = ANY (i_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::text[]',
                                           pg_catalog.array_to_string(l_text_terms, ', '))
                ELSE 'NULL::text[]'
                END,
            CASE
                WHEN 'uuid' = ANY (i_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::uuid[]',
                                           pg_catalog.array_to_string(l_uuid_terms, ', '))
                ELSE 'NULL::uuid[]'
                END,
            CASE
                WHEN 'timestamptz' = ANY (i_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::timestamptz[]',
                                           pg_catalog.array_to_string(l_timestamptz_terms, ', '))
                ELSE 'NULL::timestamptz[]'
                END);

    -- Boundaries are picked by row position, so the computation never needs
    -- min()/max() on the key: PostgreSQL defines those aggregates for the
    -- integer and text types but not for uuid.
    --
    -- The shape matters on production-sized tables:
    --   * numbered is referenced once, so it is inlined and the row_number()
    --     WindowAgg streams over the boundary-key index instead of sorting or
    --     materialising all rows.
    --   * count(*) OVER () is deliberately not used: a full-partition window
    --     aggregate buffers every row (spilling to disk for large tables) just
    --     to recover the row count. The terminal high-water boundary instead
    --     comes from a backward index scan (ORDER BY the key DESC LIMIT 1) and
    --     its boundary_no is the last chunk start plus one.
    --   * only the (N / chunk_size) start rows are materialised.
    RETURN pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils_data.migration_boundary (run_id, boundary_no, boundary_id, completed_at)
        WITH numbered AS (
            SELECT
                %1$s,
                row_number() OVER (ORDER BY %2$s) AS rn
            FROM %3$I.%4$I
        ),
        starts AS MATERIALIZED (
            SELECT
                %5$s,
                (rn - 1) / %9$s::bigint AS boundary_no
            FROM numbered
            WHERE (rn - 1) %% %9$s::bigint = 0
        )
        SELECT
            %8$s,
            boundary_no,
            %6$s AS boundary_id,
            NULL::timestamptz AS completed_at
        FROM starts
        UNION ALL
        SELECT
            %8$s,
            (SELECT max(boundary_no) + 1 FROM starts),
            terminal.boundary_id,
            NULL::timestamptz AS completed_at
        FROM (
            SELECT
                %6$s AS boundary_id
            FROM (
                SELECT %1$s
                FROM %3$I.%4$I
                ORDER BY %7$s
                LIMIT 1
            ) AS last_row
        ) AS terminal
        $chunk$,
            l_key_select_list,
            l_order_by,
            i_schema_name,
            i_table_name,
            l_key_id_list,
            l_pack_expression,
            l_order_by_desc,
            i_run_id,
            i_chunk_size);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.build_boundary_population_sql IS
    'Returns the statement that scans the driving table in boundary-key order and '
        'inserts the run''s chunk boundaries (one start per chunk plus the terminal '
        'high-water boundary) as the contiguous sequence 0..N. The caller supplies '
        'the key columns and kinds (primary_key_columns/primary_key_kinds for a '
        'primary-key run, or ordered_key_columns/ordered_key_kinds once the '
        'ordered-key path is wired); the run id and chunk size are inlined so the '
        'runner executes and the explain path explains the identical statement.';
