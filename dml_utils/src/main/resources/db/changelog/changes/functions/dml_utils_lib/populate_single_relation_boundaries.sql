CREATE OR REPLACE FUNCTION dml_utils_lib.populate_single_relation_boundaries(
    i_run_id bigint,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy,
    i_key_columns name[],
    i_key_kinds text[],
    i_chunk_size dml_utils_data.positive_integer
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- The boundary scan runs as the definer, so the effective caller must still
    -- be allowed to read the driving table.
    PERFORM dml_utils_lib.assert_caller_can_select_relation(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    -- The boundary statement is built by a dml_utils_lib helper shared with the
    -- explain path, so both execute and explain the identical SQL. It is safe
    -- because every identifier comes from the catalog and every kind from the
    -- key-kind resolver whitelist; the run id and chunk size are inlined as
    -- bigints. Boundaries are written as the contiguous sequence 0..N in one
    -- statement, so a run has exactly the chunk starts plus one terminal
    -- boundary the runner assumes; this is the only supported write path.
    IF i_chunk_by = 'blocks' THEN
        EXECUTE dml_utils_lib.build_block_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_run_id => i_run_id,
                i_chunk_size => i_chunk_size::bigint);
    ELSE
        EXECUTE dml_utils_lib.build_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_key_columns => i_key_columns,
                i_key_kinds => i_key_kinds,
                i_run_id => i_run_id,
                i_chunk_size => i_chunk_size::bigint);
    END IF;

    -- The range calculation is done once the boundaries are inserted. Record it
    -- in this worker's transaction, so it commits with the run and is the
    -- milestone a resume relies on: the boundaries are never recomputed. The
    -- chunk count is the boundary count minus the terminal high-water boundary;
    -- an empty source has no boundaries, so it is clamped to 0.
    UPDATE dml_utils_data.migration_run
    SET boundaries_calculated_at = pg_catalog.clock_timestamp(),
        total_chunks             = (SELECT greatest(pg_catalog.count(*) - 1, 0::bigint)
                                    FROM dml_utils_data.migration_boundary
                                    WHERE run_id = i_run_id)
    WHERE run_id = i_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.populate_single_relation_boundaries(
    bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name[],
    text[],
    dml_utils_data.positive_integer
    ) IS
    'Inserts a single-relation run''s contiguous 0..N boundaries in one '
        'statement and records boundaries_calculated_at and total_chunks. Runs in '
        'the caller''s context (inside populate_migration_boundaries'' definer '
        'context), with an explicit effective-caller SELECT check on the driving '
        'table.';
