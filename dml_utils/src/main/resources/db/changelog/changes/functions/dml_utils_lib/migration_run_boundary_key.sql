CREATE OR REPLACE FUNCTION dml_utils_lib.migration_run_boundary_key(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS TABLE
            (
                o_key_columns name[],
                o_key_kinds   text[]
            )
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_chunk_by            dml_utils_data.chunking_strategy;
    l_schema_name         text;
    l_table_name          text;
    l_ordered_key_columns name[];
    l_ordered_key_kinds   text[];
BEGIN
    SELECT run.chunk_by,
           run.driving_table_schema_name::text,
           run.driving_table_name::text,
           run.ordered_key_columns,
           run.ordered_key_kinds
    INTO l_chunk_by,
        l_schema_name,
        l_table_name,
        l_ordered_key_columns,
        l_ordered_key_kinds
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- A blocks run chunks by ctid and has no key. A partition-wise blocks run
    -- still resolves to no key, even though it has leaves.
    IF l_chunk_by = 'blocks' THEN
        RETURN QUERY SELECT NULL::name[], NULL::text[];
        RETURN;
    END IF;

    -- An ordered-key run applied the descriptor resolved from the parent index
    -- when it started. The same descriptor must drive every leaf; a missing one
    -- is corrupt state, not something to recompute from a leaf.
    IF l_chunk_by = 'ordered_key' THEN
        IF l_ordered_key_columns IS NULL OR l_ordered_key_kinds IS NULL THEN
            RAISE EXCEPTION 'ordered-key run % has no stored key descriptor', i_run_id
                USING ERRCODE = '55000';
        END IF;
        RETURN QUERY SELECT l_ordered_key_columns, l_ordered_key_kinds;
        RETURN;
    END IF;

    -- Primary key: re-read from the live parent on every call. The run also
    -- snapshots the descriptor at creation (primary_key_columns/kinds) so that
    -- resume_migration_run can fail closed (55000) when the live key changed;
    -- reading the live key here keeps the rendered chunk SQL tied to the catalog
    -- the scan actually runs against. Ordered-key runs render from their stored
    -- descriptor instead (above).
    RETURN QUERY
        SELECT dml_utils_lib.primary_key_columns(
                       i_schema_name => l_schema_name,
                       i_table_name => l_table_name),
               dml_utils_lib.primary_key_kinds(
                       i_schema_name => l_schema_name,
                       i_table_name => l_table_name);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_run_boundary_key IS
    'Returns the boundary-key columns and kinds a run chunks by: NULL for blocks, '
        'the stored ordered-key descriptor for ordered_key, and the driving '
        'parent''s live primary key for primary_key. Every leaf of a run must use '
        'the same key order; a primary-key run snapshots its key at creation and '
        'resume fails closed if the live key changed.';
