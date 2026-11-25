CREATE OR REPLACE FUNCTION dml_utils_lib.boundary_key_descriptor_sql(
    i_key_columns dml_utils_data.non_null_element_name_array,
    i_key_kinds dml_utils_data.non_null_element_text_array
)
    RETURNS TABLE
            (
                o_select_list     text,
                o_id_list         text,
                o_order_by        text,
                o_order_by_desc   text,
                o_pack_expression text
            )
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
    l_pack_expression text;
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

    -- Build, position by position: the SELECT list that aliases each key column
    -- as id1, id2, ..., the matching id list and the ORDER BY. All identifiers
    -- are catalog names quoted with %I/quote_ident.
    l_key_select_list := '';
    l_key_id_list := '';
    l_order_by := '';
    l_order_by_desc := '';
    FOR l_position IN 1..pg_catalog.array_length(i_key_kinds, 1)
        LOOP
        -- The argument domain rejects NULL elements; an empty name would still
        -- render a degenerate %I token. The kind itself is validated by
        -- boundary_key_pack_expression below.
            IF i_key_columns[l_position] = ''
            THEN
                RAISE EXCEPTION 'boundary-key column name must not be empty at position %',
                    l_position
                    USING ERRCODE = '22023';
            END IF;

            -- A repeated column renders a non-unique boundary key (the same
            -- column aliased and ordered twice), so reject it here where the
            -- key contract is enforced.
            IF pg_catalog.array_position(i_key_columns, i_key_columns[l_position]) IS DISTINCT FROM l_position
            THEN
                RAISE EXCEPTION 'boundary-key column % is repeated at position %',
                    i_key_columns[l_position], l_position
                    USING ERRCODE = '22023';
            END IF;

            l_key_select_list := l_key_select_list || pg_catalog.format('%s%s AS id%s',
                                                                        CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                        pg_catalog.quote_ident(
                                                                                i_key_columns[l_position]
                                                                        ),
                                                                        l_position);
            l_key_id_list := l_key_id_list || pg_catalog.format('%sid%s',
                                                                CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                l_position);
            -- The ascending fragment uses the raw (quoted) column names and is
            -- only valid inside a window ORDER BY, which is where the boundary
            -- builders use it (`row_number() OVER (ORDER BY ...)`): a window
            -- definition resolves input columns and does not see the select list's
            -- output aliases. Do not reuse it as a top-level ORDER BY over the
            -- aliased select, where a key column named id1 at another position
            -- would resolve to the wrong output alias; that is what o_order_by_desc
            -- (idN aliases) is for.
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
        END LOOP;

    l_pack_expression := dml_utils_lib.boundary_key_pack_expression(
            i_key_kinds => i_key_kinds);

    RETURN QUERY
        SELECT l_key_select_list,
               l_key_id_list,
               l_order_by,
               l_order_by_desc,
               l_pack_expression;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.boundary_key_descriptor_sql IS
    'Validates a one-to-three-column boundary key and returns the SQL fragments '
        'that read, order and pack it: o_select_list (each column quoted and '
        'aliased id1..idN), o_id_list (the same aliases), o_order_by (raw quoted '
        'column names, for a window ORDER BY only, where output aliases are not '
        'resolved), o_order_by_desc (idN aliases, for a top-level ORDER BY over '
        'o_select_list, where an output alias would shadow a same-named input '
        'column) and o_pack_expression (the migration_key row expression from '
        'boundary_key_pack_expression). o_pack_expression is a SELECT-list '
        'expression, not a standalone predicate: evaluate it in a SELECT over '
        'o_select_list (for example `SELECT <pack> AS key FROM (SELECT <select '
        'list> ...) AS row`) and read its five arrays from that key. Shared by the '
        'single-relation and per-leaf boundary builders so both render the same key '
        'logic.';
