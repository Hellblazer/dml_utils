CREATE OR REPLACE FUNCTION dml_utils_lib.render_prepared_chunk_sql(
    i_template text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_column_names text[],
    i_key_kinds text[],
    i_key_collations text[],
    i_start_values text[],
    i_end_values text[],
    i_is_final boolean
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
    l_driving_table_sentinel   constant text    := pg_catalog.chr(1);
    l_chunking_clause_sentinel constant text    := pg_catalog.chr(2);
    l_column_count                      integer;
    l_start_literals                    text[];
    l_end_literals                      text[];
    l_boxes                             text[];
    l_box                               text;
    l_driving_table                     text;
    l_chunking_clause                   text;
    l_rendered                          text;
    l_kind                              text;
    l_start_less_than_end               boolean;
    l_start_bigint                      bigint;
    l_end_bigint                        bigint;
    l_start_uuid                        uuid;
    l_end_uuid                          uuid;
    l_start_timestamptz                 timestamptz;
    l_end_timestamptz                   timestamptz;
    -- Direction of the first differing column (-1 start < end, 1 start > end,
    -- 0 all equal) and its position, which selects the box decomposition.
    l_first_difference                  integer := 0;
    l_first_difference_position         integer := 0;
BEGIN
    -- The per-chunk render consumes only the run-invariant context prepared once
    -- by prepare_chunk_sql_render; it reads no catalog and validates no template.
    IF i_template IS NULL THEN
        RAISE EXCEPTION 'prepared template must not be NULL'
            USING ERRCODE = '22023';
    END IF;

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
    IF i_is_final IS NULL THEN
        RAISE EXCEPTION 'i_is_final must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- The column names, kinds and both boundary arrays are indexed from 1 below, so
    -- they must be one-dimensional with a lower bound of 1 and the same non-zero
    -- length. array_length of an empty/NULL array is NULL, which the length
    -- comparisons below reject.
    IF pg_catalog.array_ndims(i_column_names) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_column_names, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_key_kinds) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_kinds, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_start_values) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_start_values, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_end_values) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_end_values, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_length(i_column_names, 1) IS NULL
        OR pg_catalog.array_length(i_column_names, 1) IS DISTINCT FROM pg_catalog.array_length(i_key_kinds, 1)
        OR pg_catalog.array_length(i_column_names, 1) IS DISTINCT FROM pg_catalog.array_length(i_start_values, 1)
        OR pg_catalog.array_length(i_column_names, 1) IS DISTINCT FROM pg_catalog.array_length(i_end_values, 1)
    THEN
        RAISE EXCEPTION 'column names, kinds and boundary values must be one-dimensional, 1-based, same-length and non-empty'
            USING ERRCODE = '22023';
    END IF;

    -- The prepared context qualified its column names with the alias passed to
    -- prepare_chunk_sql_render. This call supplies the table per chunk, so a
    -- different alias would render a predicate that no longer binds the driving
    -- table; reject it rather than emit SQL that fails in the worker.
    FOR l_position IN 1..pg_catalog.array_length(i_column_names, 1)
        LOOP
            IF i_column_names[l_position] IS NULL
                OR NOT pg_catalog.starts_with(
                        i_column_names[l_position],
                        pg_catalog.quote_ident(i_table_alias) || '.')
            THEN
                RAISE EXCEPTION 'column name % is not qualified with alias %',
                    i_column_names[l_position], i_table_alias
                    USING ERRCODE = '22023';
            END IF;
        END LOOP;

    -- Validate each boundary value and build its cast literal. The kind selects the
    -- explicit cast and is interpolated into the SQL, so it must be one of the known
    -- kinds; the values are quoted with %L.
    l_column_count := pg_catalog.array_length(i_column_names, 1);
    l_start_literals := ARRAY []::text[];
    l_end_literals := ARRAY []::text[];
    FOR l_position IN 1..l_column_count
        LOOP
            l_kind := i_key_kinds[l_position];

            -- A NULL kind would slip past NOT IN (NULL NOT IN (...) is NULL, not
            -- true), so guard it explicitly.
            IF l_kind IS NULL OR l_kind NOT IN ('bigint', 'text', 'uuid', 'timestamptz') THEN
                RAISE EXCEPTION 'unsupported key kind %', l_kind
                    USING ERRCODE = '22023';
            END IF;

            -- %L renders a NULL as an unquoted NULL, so a missing boundary value
            -- would render a predicate that silently matches no rows (or narrows a
            -- partial row) while the chunk is still marked complete; fail loudly.
            IF i_start_values[l_position] IS NULL OR i_end_values[l_position] IS NULL THEN
                RAISE EXCEPTION 'boundary % value for column % must not be NULL',
                    CASE WHEN i_start_values[l_position] IS NULL THEN 'start' ELSE 'end' END,
                    i_column_names[l_position]
                    USING ERRCODE = '22023';
            END IF;

            -- A non-NULL value that is not a valid literal for its kind would be
            -- emitted into the chunk SQL and only fail when a worker parses it;
            -- validate by casting here (and keep the typed value for the range check
            -- below), so the failure is not deferred. text accepts any value, so only
            -- bigint, uuid and timestamptz need a cast. Catch the invalid-text and
            -- invalid-datetime conditions (22023 / 22P02 / 22007 / 22008 / 22009); an
            -- unexpected error is re-raised with its SQLSTATE.
            BEGIN
                IF l_kind = 'bigint' THEN
                    l_start_bigint := i_start_values[l_position]::bigint;
                    l_end_bigint := i_end_values[l_position]::bigint;
                    IF l_first_difference = 0 AND l_start_bigint IS DISTINCT FROM l_end_bigint THEN
                        l_first_difference := CASE WHEN l_start_bigint < l_end_bigint THEN -1 ELSE 1 END;
                        l_first_difference_position := l_position;
                    END IF;
                ELSIF l_kind = 'uuid' THEN
                    l_start_uuid := i_start_values[l_position]::uuid;
                    l_end_uuid := i_end_values[l_position]::uuid;
                    IF l_first_difference = 0 AND l_start_uuid IS DISTINCT FROM l_end_uuid THEN
                        l_first_difference := CASE WHEN l_start_uuid < l_end_uuid THEN -1 ELSE 1 END;
                        l_first_difference_position := l_position;
                    END IF;
                ELSIF l_kind = 'timestamptz' THEN
                    l_start_timestamptz := i_start_values[l_position]::timestamptz;
                    l_end_timestamptz := i_end_values[l_position]::timestamptz;
                    IF l_first_difference = 0
                        AND l_start_timestamptz IS DISTINCT FROM l_end_timestamptz
                    THEN
                        l_first_difference := CASE
                                                  WHEN l_start_timestamptz < l_end_timestamptz THEN -1
                                                  ELSE 1
                            END;
                        l_first_difference_position := l_position;
                    END IF;
                ELSE
                    -- text must be ordered under the column's own collation, which
                    -- is what the predicate uses. The collation was resolved once by
                    -- prepare_chunk_sql_render: NULL means the column is not in the
                    -- catalog (compare under the default collation), otherwise it is
                    -- applied with an explicit COLLATE.
                    IF l_first_difference = 0
                        AND i_start_values[l_position] IS DISTINCT FROM i_end_values[l_position]
                    THEN
                        IF i_key_collations[l_position] IS NULL THEN
                            l_start_less_than_end :=
                                    i_start_values[l_position] < i_end_values[l_position];
                        ELSE
                            EXECUTE pg_catalog.format(
                                    'SELECT %L::text COLLATE %s < %L::text COLLATE %s',
                                    i_start_values[l_position], i_key_collations[l_position],
                                    i_end_values[l_position], i_key_collations[l_position])
                                INTO l_start_less_than_end;
                        END IF;

                        l_first_difference := CASE WHEN l_start_less_than_end THEN -1 ELSE 1 END;
                        l_first_difference_position := l_position;
                    END IF;
                END IF;
            EXCEPTION
                WHEN invalid_parameter_value
                    OR invalid_text_representation
                    OR invalid_datetime_format
                    OR datetime_field_overflow
                    OR SQLSTATE '22009' THEN
                    RAISE EXCEPTION
                        'boundary value for column % is not a valid %',
                        i_column_names[l_position], l_kind
                        USING ERRCODE = '22023';
            END;

            l_start_literals := l_start_literals || pg_catalog.format('%L::%s',
                                                                      i_start_values[l_position],
                                                                      l_kind);
            l_end_literals := l_end_literals || pg_catalog.format('%L::%s',
                                                                  i_end_values[l_position],
                                                                  l_kind);
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

    -- Emit the chunk as a disjunction of axis-aligned boxes that exactly tiles the
    -- lexicographic range [start, end). Each box is a prefix of equalities plus a
    -- range on the next column, so PostgreSQL estimates it column by column and
    -- consumes it as a tight index range. A single row-value comparison instead
    -- multiplies the two bounds' selectivities independently, which overestimates
    -- a mid-table chunk enough to prefer a sequential scan of the whole table.
    --
    -- Let d be the first column whose bounds differ. The boxes are the lower tail
    -- (c_d = start_d and the suffix >= the lower suffix), the middle
    -- (start_d < c_d < end_d) and the upper tail (c_d = end_d and the suffix < the
    -- upper suffix, inclusive on the last column for the final chunk): 2*(n-d)+1
    -- boxes in all. A key that differs only in its last column is a single scalar
    -- range, and a key whose bounds are all equal is a single row.
    l_boxes := ARRAY []::text[];
    IF l_first_difference_position = 0 THEN
        -- All columns equal: exactly one row (a final chunk only; the check above
        -- rejects it otherwise).
        l_box := '';
        FOR l_position IN 1..l_column_count
            LOOP
                l_box := l_box || pg_catalog.format('%s%s = %s',
                                                    CASE WHEN l_position > 1 THEN ' AND ' ELSE '' END,
                                                    i_column_names[l_position],
                                                    l_start_literals[l_position]);
            END LOOP;
        l_boxes := l_boxes || l_box;
    ELSIF l_first_difference_position = l_column_count THEN
        -- Only the last column differs: one box, a half-open (final: closed) scalar
        -- range after the fixed prefix.
        l_box := '';
        FOR l_position IN 1..l_column_count - 1
            LOOP
                l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                    i_column_names[l_position],
                                                    l_start_literals[l_position]);
            END LOOP;
        l_box := l_box || pg_catalog.format('%s >= %s AND %s %s %s',
                                            i_column_names[l_column_count],
                                            l_start_literals[l_column_count],
                                            i_column_names[l_column_count],
                                            CASE WHEN i_is_final THEN '<=' ELSE '<' END,
                                            l_end_literals[l_column_count]);
        l_boxes := l_boxes || l_box;
    ELSE
        -- Lower tail: c_d = start_d and the suffix >= the lower suffix. One box per
        -- suffix column: c_j > start_j, and >= start_n on the last column.
        FOR l_j IN l_first_difference_position + 1..l_column_count
            LOOP
                l_box := '';
                FOR l_position IN 1..l_first_difference_position
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            i_column_names[l_position],
                                                            l_start_literals[l_position]);
                    END LOOP;
                FOR l_position IN l_first_difference_position + 1..l_j - 1
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            i_column_names[l_position],
                                                            l_start_literals[l_position]);
                    END LOOP;
                IF l_j < l_column_count THEN
                    l_box := l_box || pg_catalog.format('%s > %s',
                                                        i_column_names[l_j],
                                                        l_start_literals[l_j]);
                ELSE
                    l_box := l_box || pg_catalog.format('%s >= %s',
                                                        i_column_names[l_j],
                                                        l_start_literals[l_j]);
                END IF;
                l_boxes := l_boxes || l_box;
            END LOOP;

        -- Middle: start_d < c_d < end_d, after the fixed prefix.
        l_box := '';
        FOR l_position IN 1..l_first_difference_position - 1
            LOOP
                l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                    i_column_names[l_position],
                                                    l_start_literals[l_position]);
            END LOOP;
        l_box := l_box || pg_catalog.format('%s > %s AND %s < %s',
                                            i_column_names[l_first_difference_position],
                                            l_start_literals[l_first_difference_position],
                                            i_column_names[l_first_difference_position],
                                            l_end_literals[l_first_difference_position]);
        l_boxes := l_boxes || l_box;

        -- Upper tail: c_d = end_d and the suffix < the upper suffix. One box per
        -- suffix column: c_j < end_j, and <= end_n on the last column for the
        -- final chunk (so the captured maximum row is processed).
        FOR l_j IN l_first_difference_position + 1..l_column_count
            LOOP
                l_box := '';
                FOR l_position IN 1..l_first_difference_position - 1
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            i_column_names[l_position],
                                                            l_start_literals[l_position]);
                    END LOOP;
                l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                    i_column_names[l_first_difference_position],
                                                    l_end_literals[l_first_difference_position]);
                FOR l_position IN l_first_difference_position + 1..l_j - 1
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            i_column_names[l_position],
                                                            l_end_literals[l_position]);
                    END LOOP;
                IF l_j < l_column_count THEN
                    l_box := l_box || pg_catalog.format('%s < %s',
                                                        i_column_names[l_j],
                                                        l_end_literals[l_j]);
                ELSE
                    l_box := l_box || pg_catalog.format('%s %s %s',
                                                        i_column_names[l_j],
                                                        CASE WHEN i_is_final THEN '<=' ELSE '<' END,
                                                        l_end_literals[l_j]);
                END IF;
                l_boxes := l_boxes || l_box;
            END LOOP;
    END IF;

    -- Parenthesized so the disjunction drops into a template clause verbatim.
    l_chunking_clause := '(' || pg_catalog.array_to_string(l_boxes, ' OR ') || ')';

    -- A substituted value could in principle contain a sentinel character; reject
    -- that so the substitution below stays unambiguous.
    IF pg_catalog.strpos(l_chunking_clause, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'template or identifier contains a reserved substitution character'
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
        'prepare_chunk_sql_render: it validates and casts the chunk''s start/end '
        'boundary values, builds the axis-aligned box disjunction that exactly tiles '
        'the lexicographic range, substitutes the caller''s driving table (the parent '
        'or one leaf) for the template''s driving-table sentinel, and substitutes the '
        'clause for its sentinel. i_table_alias must be the alias the context was '
        'prepared with, because the column names are already qualified with it; a '
        'different alias is rejected. The final chunk uses an inclusive upper bound; '
        'a reversed range, a NULL value or a value that is not a valid literal for '
        'its key kind is rejected. A text key is ordered under the precomputed '
        'column collation, matching the predicate.';
