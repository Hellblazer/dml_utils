CREATE OR REPLACE FUNCTION dml_utils_lib.build_block_leaf_boundary_population_sql(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_run_id dml_utils_data.non_null_bigint,
    i_chunk_size dml_utils_data.positive_bigint,
    i_leaf_ordinal dml_utils_data.positive_integer
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- The run id, chunk size and leaf ordinal are inlined (all integers, so no
    -- injection surface); their parameter domains reject a NULL or non-positive
    -- value before the body runs.
    -- the manifest key.

    -- One leaf's block chunk starts, appended after every boundary already
    -- committed for the run. The block count is read from the leaf's own heap at
    -- execution time; a leaf with no blocks yields no starts. There is no
    -- per-leaf terminal: finalize_migration_boundaries writes the one terminal
    -- boundary from the last non-empty leaf.
    RETURN pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils_data.migration_boundary
            (run_id, boundary_no, boundary_id, leaf_ordinal, completed_at)
        WITH params AS (
            SELECT pg_catalog.pg_relation_size(%2$L::pg_catalog.regclass)
                       / pg_catalog.current_setting('block_size')::bigint AS block_count
        ),
        counts AS (
            SELECT block_count,
                   (block_count + %3$s::bigint - 1) / %3$s::bigint AS n_starts
            FROM params
        ),
        starts AS (
            SELECT g - 1 AS leaf_boundary_no,
                   (g - 1) * %3$s::bigint AS start_block
            FROM counts,
                 pg_catalog.generate_series(1, counts.n_starts) AS g
        ),
        base AS (
            SELECT coalesce(max(boundary_no), -1) + 1 AS first_boundary_no
            FROM dml_utils_data.migration_boundary
            WHERE run_id = %1$s
        )
        SELECT %1$s,
               base.first_boundary_no + starts.leaf_boundary_no,
               ROW(ARRAY[start_block]::bigint[], NULL::text[], NULL::uuid[],
                   NULL::timestamptz[])::dml_utils_data.migration_key,
               %4$s,
               NULL::timestamptz
        FROM starts
                 CROSS JOIN base
        $chunk$,
            i_run_id,
            pg_catalog.format('%I.%I', i_schema_name, i_table_name),
            i_chunk_size,
            i_leaf_ordinal);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.build_block_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint,
    dml_utils_data.positive_integer
    ) IS
    'Returns the statement that inserts one blocks leaf''s chunk starts (one '
        'every i_chunk_size heap blocks, no per-leaf terminal) after the '
        'boundaries already committed for the run, tagged with the leaf ordinal.';
