CREATE OR REPLACE FUNCTION dml_utils_lib.render_prepared_chunk_sql(
    i_template dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_column_names dml_utils_data.non_null_element_text_array,
    i_key_kinds dml_utils_data.non_null_element_text_array,
    i_key_collations text[],
    i_start_values text[],
    i_end_values text[],
    i_is_final dml_utils_data.non_null_boolean
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    -- The sentinels prepare_chunk_sql_render left in the prepared template (and
    -- rejects in a substituted value). Both are checked on the chunking clause so
    -- no substituted value can reintroduce a token.
    l_driving_table_sentinel   constant text := pg_catalog.chr(1);
    l_chunking_clause_sentinel constant text := pg_catalog.chr(2);
    l_column_count                      integer;
    l_alias_prefix                      text;
    l_start_literals                    text[];
    l_end_literals                      text[];
    l_first_difference                  integer;
    l_first_difference_position         integer;
    l_boxes                             text[];
    l_driving_table                     text;
    l_chunking_clause                   text;
    l_rendered                          text;
BEGIN
    -- The per-chunk render consumes only the run-invariant context prepared once
    -- by prepare_chunk_sql_render; it reads no catalog and validates no template.

    -- The prepared template must carry exactly the clause sentinel, or the
    -- substituted clause would never be placed and the chunk would render with no
    -- predicate. It must also carry the driving-table sentinel, or the chunk would
    -- render against whatever table the template hard-coded instead of the caller's.
    IF pg_catalog.strpos(i_template, l_chunking_clause_sentinel) = 0 THEN
        RAISE EXCEPTION 'prepared template is missing the chunking-clause placeholder'
            USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.strpos(i_template, l_driving_table_sentinel) = 0 THEN
        RAISE EXCEPTION 'prepared template is missing the driving-table placeholder'
            USING ERRCODE = '22023';
    END IF;

    -- The table is resolved per render, so a partition-wise run can target one
    -- leaf at a time while keeping the run-invariant key context. A value that
    -- cannot be a quoted identifier must not reach the substitution.
    l_driving_table := pg_catalog.format('%I.%I %I',
                                         i_schema_name, i_table_name, i_table_alias);
    IF pg_catalog.strpos(l_driving_table, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_driving_table, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'identifier contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    -- i_is_final selects the upper bound (inclusive for the final chunk, exclusive
    -- otherwise); a NULL would silently take the non-final branch, so reject it.

    -- The prepared context carries one collation per key column (NULL for a kind
    -- that does not order by collation). A missing or misaligned array would make
    -- the helper compare a text key under the default collation instead of the
    -- column's, so reject it here where the parameter is still named.
    IF i_key_collations IS NULL
        OR pg_catalog.array_ndims(i_key_collations) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_collations, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_length(i_key_collations, 1) IS DISTINCT FROM pg_catalog.array_length(i_column_names, 1)
    THEN
        RAISE EXCEPTION 'key collations must be a one-dimensional, 1-based array aligned with the column names'
            USING ERRCODE = '22023';
    END IF;

    -- Validate the boundary values and build their typed literals. The helper
    -- also rejects malformed column/kind/value arrays, so the array length below
    -- is safe to use.
    SELECT literals.o_start_literals,
           literals.o_end_literals,
           literals.o_first_difference,
           literals.o_first_difference_position
    INTO l_start_literals, l_end_literals,
        l_first_difference, l_first_difference_position
    FROM dml_utils_lib.build_chunk_boundary_literals(
                 i_column_names => i_column_names,
                 i_key_kinds => i_key_kinds,
                 i_key_collations => i_key_collations,
                 i_start_values => i_start_values,
                 i_end_values => i_end_values) AS literals;

    -- The prepared context qualified its column names with the alias passed to
    -- prepare_chunk_sql_render. This call supplies the table per chunk, so a
    -- different alias would render a predicate that no longer binds the driving
    -- table; reject it rather than emit SQL that fails in the worker.
    l_column_count := pg_catalog.array_length(i_column_names, 1);
    l_alias_prefix := pg_catalog.quote_ident(i_table_alias) || '.';
    FOR l_position IN 1..l_column_count
        LOOP
        -- The element is interpolated verbatim into the predicate, so it must
        -- be exactly the alias-qualified identifier build_key_render_columns
        -- produced: the prefix followed by one (quoted or simple) identifier.
        -- A prefix match alone would accept "t.a OR true --". The C collation
        -- makes [a-z] a byte range: under a linguistic collation it would
        -- also accept uppercase, which the producer always quotes.
            IF i_column_names[l_position] IS NULL
                OR NOT pg_catalog.starts_with(i_column_names[l_position], l_alias_prefix)
                OR pg_catalog.substr(i_column_names[l_position],
                                     pg_catalog.length(l_alias_prefix) + 1)
                       COLLATE pg_catalog."C"
                   !~ '^"([^"]|"")+"$|^[a-z_][a-z0-9_$]*$'
            THEN
                RAISE EXCEPTION 'column name % is not a single identifier qualified with alias %',
                    i_column_names[l_position], i_table_alias
                    USING ERRCODE = '22023';
            END IF;
        END LOOP;

    -- Reject a reversed chunk range (or an empty non-final one), which would
    -- render a predicate that matches no rows while the worker still marks the
    -- boundary complete. A final chunk may be a single row (start = end); any
    -- other chunk is half-open and needs start < end.
    IF l_first_difference > 0
        OR (NOT i_is_final AND l_first_difference = 0)
    THEN
        RAISE EXCEPTION 'chunk start boundary must be less than the end boundary'
            USING ERRCODE = '22023';
    END IF;

    l_boxes := dml_utils_lib.build_chunk_range_boxes(
            i_column_names => i_column_names,
            i_start_literals => l_start_literals,
            i_end_literals => l_end_literals,
            i_first_difference_position => l_first_difference_position,
            i_is_final => i_is_final);

    -- Parenthesized so the disjunction drops into a template clause verbatim.
    l_chunking_clause := '(' || pg_catalog.array_to_string(l_boxes, ' OR ') || ')';

    -- A substituted value could in principle contain a sentinel character; reject
    -- that so the substitution below stays unambiguous.
    IF pg_catalog.strpos(l_chunking_clause, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'the chunking clause contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    -- Substitute the driving table first and the clause second. Substituted
    -- values cannot contain either sentinel, so neither replacement is re-scanned.
    l_rendered := pg_catalog.replace(i_template, l_driving_table_sentinel, l_driving_table);
    l_rendered := pg_catalog.replace(l_rendered, l_chunking_clause_sentinel, l_chunking_clause);
    RETURN l_rendered;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.render_prepared_chunk_sql IS
    'Renders one chunk from the run-invariant context returned by '
        'prepare_chunk_sql_render: build_chunk_boundary_literals validates and '
        'casts the chunk''s start/end values, build_chunk_range_boxes builds the '
        'axis-aligned box disjunction that exactly tiles the lexicographic range, '
        'and this routine substitutes the caller''s driving table (the parent or '
        'one leaf) for the template''s driving-table sentinel and the clause for '
        'its sentinel. i_table_alias must be the alias the context was prepared '
        'with, because the column names are already qualified with it; a different '
        'alias or a column name that is not one alias-qualified identifier is '
        'rejected. The final chunk uses an inclusive upper bound; a '
        'reversed range, a NULL value or a value that is not a valid literal for '
        'its key kind is rejected. A text key is ordered under the precomputed '
        'column collation, matching the predicate.';
