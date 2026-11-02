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
    l_descriptor record;
BEGIN
    -- The run id and chunk size are inlined (both bigint, so no injection
    -- surface). A NULL run id would render an empty token and a non-positive
    -- chunk size would render a degenerate divisor.
    IF i_run_id IS NULL OR i_chunk_size IS NULL OR i_chunk_size <= 0 THEN
        RAISE EXCEPTION 'run id must not be NULL and chunk size must be positive'
            USING ERRCODE = '22023';
    END IF;

    -- The shared helper validates the key descriptor and renders the select,
    -- order and pack fragments. Both the single-relation and per-leaf builders
    -- use it, so the key logic cannot drift between them.
    SELECT *
    INTO l_descriptor
    FROM dml_utils_lib.boundary_key_descriptor_sql(
            i_key_columns => i_key_columns,
            i_key_kinds => i_key_kinds);

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
            l_descriptor.o_select_list,
            l_descriptor.o_order_by,
            i_schema_name,
            i_table_name,
            l_descriptor.o_id_list,
            l_descriptor.o_pack_expression,
            l_descriptor.o_order_by_desc,
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
