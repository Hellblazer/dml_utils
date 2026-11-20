CREATE OR REPLACE FUNCTION dml_utils_lib.populate_single_relation_boundaries(
    i_run_id dml_utils_data.non_null_bigint,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy,
    i_key_columns dml_utils_data.non_null_element_name_array,
    i_key_kinds dml_utils_data.non_null_element_text_array,
    i_chunk_size dml_utils_data.positive_integer
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run         record;
    l_updated     integer;
    l_key_columns name[];
    l_key_kinds   text[];
BEGIN
    -- Lock the run row before checking its state: a concurrent population or
    -- cancellation must serialize here, or the loser would fail with a raw
    -- duplicate-key error from the boundary INSERT instead of the controlled
    -- 55000. The lock also covers the final UPDATE below.
    SELECT run.chunk_by,
           run.chunk_size,
           run.partition_wise,
           run.driving_table_schema_name,
           run.driving_table_name,
           run.boundaries_calculated_at,
           run.cancelled_at
    INTO l_run
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id
        FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;
    IF l_run.boundaries_calculated_at IS NOT NULL OR l_run.cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION 'run % is already calculated or cancelled', i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- A partition-wise run is calculated per leaf and finalized through
    -- append_terminal_boundary. Parent-level boundaries here would freeze its
    -- milestone and make calculate_migration_run_boundaries skip every leaf, so
    -- the caller's descriptor cannot make this safe.
    IF l_run.partition_wise THEN
        RAISE EXCEPTION 'run % is partition-wise and has no single-relation boundaries', i_run_id
            USING ERRCODE = '22023';
    END IF;

    -- The caller supplies the configuration to render with, but the run row is
    -- what the runner later reads. A direct caller must not be able to populate
    -- a run it did not create with a different strategy, chunk size or key.
    IF i_schema_name IS DISTINCT FROM l_run.driving_table_schema_name::text
        OR i_table_name IS DISTINCT FROM l_run.driving_table_name::text
    THEN
        RAISE EXCEPTION 'run % drives %.%, not %.%',
            i_run_id,
            l_run.driving_table_schema_name, l_run.driving_table_name,
            i_schema_name, i_table_name
            USING ERRCODE = '22023';
    END IF;
    IF i_chunk_by IS DISTINCT FROM l_run.chunk_by THEN
        RAISE EXCEPTION 'run % chunks by %, not %', i_run_id, l_run.chunk_by, i_chunk_by
            USING ERRCODE = '22023';
    END IF;
    IF i_chunk_size::bigint IS DISTINCT FROM l_run.chunk_size THEN
        RAISE EXCEPTION 'run % has chunk size %, not %', i_run_id,
            l_run.chunk_size, i_chunk_size
            USING ERRCODE = '22023';
    END IF;
    -- Read the key into variables: a scalar subquery over a set-returning
    -- helper would silently yield NULL (and skip the check) if it returned no
    -- row.
    SELECT boundary_key.o_key_columns, boundary_key.o_key_kinds
    INTO l_key_columns, l_key_kinds
    FROM dml_utils_lib.migration_run_boundary_key(i_run_id => i_run_id) AS boundary_key;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'run % has no boundary key', i_run_id
            USING ERRCODE = '55000';
    END IF;
    IF l_key_columns IS DISTINCT FROM i_key_columns
        OR l_key_kinds IS DISTINCT FROM i_key_kinds
    THEN
        RAISE EXCEPTION 'key columns/kinds do not match run %''s boundary key', i_run_id
            USING ERRCODE = '22023';
    END IF;

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
    WHERE run_id = i_run_id
      AND boundaries_calculated_at IS NULL
      AND cancelled_at IS NULL;

    GET DIAGNOSTICS l_updated = ROW_COUNT;
    IF l_updated <> 1 THEN
        RAISE EXCEPTION 'run % was calculated concurrently', i_run_id
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.populate_single_relation_boundaries IS
    'Inserts a single-relation run''s contiguous 0..N boundaries in one '
        'statement and records boundaries_calculated_at and total_chunks. Only a '
        'fresh, uncancelled single-relation run can be populated (P0002/55000 '
        'otherwise; a partition-wise run is 22023, since it is calculated per '
        'leaf), so a repeat call never moves the milestone or hits a raw '
        'duplicate-key error. Runs in the caller''s context (inside '
        'populate_migration_boundaries'' definer context), with an explicit '
        'effective-caller SELECT check on the driving table.';
