CREATE OR REPLACE FUNCTION dml_utils_lib.render_block_chunk_sql(
    i_sql_text dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_start_block dml_utils_data.non_negative_bigint,
    i_end_block dml_utils_data.non_negative_bigint
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
    SET search_path = pg_catalog
AS
$$
DECLARE
    -- Control-character sentinels that stand in for one token each while the
    -- other token is being replaced, so a substituted value can never be
    -- re-scanned or rewritten.
    l_driving_table_sentinel   constant text := pg_catalog.chr(1);
    l_chunking_clause_sentinel constant text := pg_catalog.chr(2);
    l_driving_table                     text;
    l_chunking_clause                   text;
    l_rendered                          text;
BEGIN
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- A reversed or empty range would render a predicate that matches no rows
    -- while the chunk is still marked complete. The end must also fit tid's block
    -- space: a tid's block number is a 32-bit unsigned value, so the highest
    -- addressable block is 4294967295 and (4294967296,0) is not a valid literal.
    -- A relation whose terminal one-past-end block is 2^32 (only reachable at the
    -- 32 TB / 8 kB block limit) therefore cannot be chunked by blocks; the error
    -- names the limit rather than failing later inside EXPLAIN/EXECUTE.
    IF i_start_block < 0 OR i_end_block <= i_start_block THEN
        RAISE EXCEPTION 'block range must satisfy 0 <= start < end'
            USING ERRCODE = '22023';
    END IF;

    IF i_end_block > 4294967295 THEN
        RAISE EXCEPTION 'block range end must not exceed 4294967295'
            USING ERRCODE = '22023';
    END IF;

    -- The driving table is referenced as "<schema>.<table> <alias>" so the
    -- template's column references can use the alias.
    l_driving_table := pg_catalog.format('%I.%I %I', i_schema_name, i_table_name, i_table_alias);

    -- A half-open ctid range over whole blocks. The tid literal is quoted (for
    -- example '(1000,0)'::tid) and the ',0' offset makes the range cover the
    -- whole block; the range is half-open for every chunk, including the
    -- terminal one, whose end is the one-past-end block count.
    l_chunking_clause := pg_catalog.format(
            '((%I.ctid) >= (%L::tid) AND (%I.ctid) < (%L::tid))',
            i_table_alias,
            pg_catalog.format('(%s,0)', i_start_block),
            i_table_alias,
            pg_catalog.format('(%s,0)', i_end_block));

    -- A quoted identifier, or the caller's template, could in principle contain a
    -- sentinel character; reject that so the substitution below stays unambiguous.
    IF pg_catalog.strpos(l_driving_table, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_driving_table, l_chunking_clause_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_chunking_clause_sentinel) > 0
        OR pg_catalog.strpos(i_sql_text, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(i_sql_text, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'template or identifier contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    -- Replace each token with its sentinel first, then expand the sentinels. The
    -- substituted values are inserted last and are never re-scanned.
    l_rendered := pg_catalog.replace(i_sql_text, '<driving_table>', l_driving_table_sentinel);
    l_rendered := pg_catalog.replace(l_rendered, '<chunking_clause>', l_chunking_clause_sentinel);
    l_rendered := pg_catalog.replace(l_rendered, l_driving_table_sentinel, l_driving_table);
    l_rendered := pg_catalog.replace(l_rendered, l_chunking_clause_sentinel, l_chunking_clause);

    RETURN l_rendered;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.render_block_chunk_sql IS
    'Returns the SQL template with <driving_table> replaced by the table and '
        '<chunking_clause> replaced by a half-open ctid range over the block range '
        '[i_start_block, i_end_block), for example ((t.ctid) >= (''(0,0)''::tid) '
        'AND (t.ctid) < (''(1000,0)''::tid)). Every chunk, including the terminal '
        'one, uses a half-open range; the terminal end is the one-past-end block '
        'count. Raises 22023 for a reversed or empty range, or an end block above '
        '4294967295 (the largest tid block number), so a relation whose '
        'one-past-end block is 2^32 cannot be chunked by blocks.';
