CREATE OR REPLACE FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_run_id dml_utils_data.non_null_bigint,
    i_chunk_size dml_utils_data.positive_bigint
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- The run id and chunk size are inlined (both bigint, so no injection
    -- surface); their parameter domains reject a NULL run id and a NULL or
    -- non-positive chunk size before the body runs.

    -- The statement computes the main-fork block count at execution time (the
    -- count the workers see) using the server block size, so it is O(1) and does
    -- not scan the table. It places a start boundary every i_chunk_size blocks
    -- (boundary_no 0..n_starts-1) and appends a one-past-end terminal boundary
    -- (boundary_no n_starts), all as bigint block numbers packed into a
    -- migration_key. Every chunk is then the half-open block range
    -- [start, next_start). A table with no blocks yields no boundaries.
    RETURN pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils_data.migration_boundary (run_id, boundary_no, boundary_id, completed_at)
        WITH params AS (
            SELECT pg_catalog.pg_relation_size(%2$L::pg_catalog.regclass)
                       / pg_catalog.current_setting('block_size')::bigint AS block_count
        ),
        counts AS (
            SELECT block_count,
                   -- Compute the start count without block_count + chunk_size - 1,
                   -- which overflows bigint for a huge (positive bigint) chunk
                   -- size and would silently produce no starts.
                   CASE
                       WHEN block_count = 0 THEN 0
                       ELSE (block_count - 1) / %3$s::bigint + 1
                       END AS n_starts
            FROM params
        ),
        starts AS (
            SELECT g - 1 AS boundary_no,
                   (g - 1) * %3$s::bigint AS start_block
            FROM counts,
                 pg_catalog.generate_series(1, counts.n_starts) AS g
        )
        SELECT %1$s,
               boundary_no,
               ROW(ARRAY[start_block]::bigint[], NULL::text[], NULL::uuid[],
                   NULL::timestamptz[])::dml_utils_data.migration_key,
               NULL::timestamptz
        FROM starts
        UNION ALL
        SELECT %1$s,
               c.n_starts,
               ROW(ARRAY[c.block_count]::bigint[], NULL::text[], NULL::uuid[],
                   NULL::timestamptz[])::dml_utils_data.migration_key,
               NULL::timestamptz
        FROM counts AS c
        WHERE c.block_count > 0
        $chunk$,
            i_run_id,
            pg_catalog.format('%I.%I', i_schema_name, i_table_name),
            i_chunk_size);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.build_block_boundary_population_sql IS
    'Returns the statement that inserts a block-chunked run''s boundaries as the '
        'contiguous sequence 0..N: one start boundary every i_chunk_size heap '
        'blocks plus a one-past-end terminal boundary, each stored as a bigint '
        'block number in migration_key. The block count comes from '
        'pg_relation_size at execution time, so no table scan is needed. The run '
        'id and chunk size are inlined so the runner executes and the explain '
        'path explains the identical statement.';
