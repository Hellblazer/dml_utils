CREATE OR REPLACE FUNCTION dml_utils_lib.build_leaf_boundary_population_sql(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_key_columns dml_utils_data.non_null_element_name_array,
    i_key_kinds dml_utils_data.non_null_element_text_array,
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
DECLARE
    l_descriptor record;
BEGIN
    -- The run id, chunk size and leaf ordinal are inlined (all integers, so no
    -- injection surface); their parameter domains reject a NULL or non-positive
    -- value before the body runs.

    SELECT o_select_list,
           o_id_list,
           o_order_by,
           o_pack_expression
    INTO l_descriptor
    FROM dml_utils_lib.boundary_key_descriptor_sql(
            i_key_columns => i_key_columns,
            i_key_kinds => i_key_kinds);

    -- One leaf's chunk starts, appended after every boundary already committed
    -- for the run. The base number comes from max(boundary_no) in this statement's
    -- snapshot, which does NOT make concurrent appends contiguous: two overlapping
    -- executions read the same base and collide on the (run_id, boundary_no)
    -- primary key. Contiguity rests on the caller serializing leaves:
    -- calculate_migration_run_boundaries holds the run's calculation advisory lock
    -- and launches one leaf transaction at a time, waiting for each. A future
    -- parallel leaf calculation would need its own serialization here.
    -- There is deliberately no per-leaf terminal: the only terminal boundary is
    -- written by finalize_migration_boundaries from the last non-empty leaf.
    RETURN pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils_data.migration_boundary
            (run_id, boundary_no, boundary_id, leaf_ordinal, completed_at)
        WITH numbered AS (
            SELECT
                %1$s,
                pg_catalog.row_number() OVER (ORDER BY %2$s) AS rn
            FROM %3$I.%4$I
        ),
        starts AS MATERIALIZED (
            SELECT
                %5$s,
                (rn - 1) / %8$s::bigint AS leaf_boundary_no
            FROM numbered
            WHERE (rn - 1) %% %8$s::bigint = 0
        ),
        base AS (
            SELECT coalesce(pg_catalog.max(boundary_no), -1) + 1 AS first_boundary_no
            FROM dml_utils_data.migration_boundary
            WHERE run_id = %7$s
        )
        SELECT
            %7$s,
            base.first_boundary_no + starts.leaf_boundary_no,
            %6$s AS boundary_id,
            %9$s,
            NULL::timestamptz
        FROM starts
                 CROSS JOIN base
        $chunk$,
            l_descriptor.o_select_list,
            l_descriptor.o_order_by,
            i_schema_name,
            i_table_name,
            l_descriptor.o_id_list,
            l_descriptor.o_pack_expression,
            i_run_id,
            i_chunk_size,
            i_leaf_ordinal);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.build_leaf_boundary_population_sql IS
    'Returns the statement that scans one leaf in boundary-key order and inserts '
        'its chunk starts (no per-leaf terminal) after the boundaries already '
        'committed for the run, tagged with the leaf ordinal. The caller supplies '
        'the key columns/kinds resolved once from the parent.';
