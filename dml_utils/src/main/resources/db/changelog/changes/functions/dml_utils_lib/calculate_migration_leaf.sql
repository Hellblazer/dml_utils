CREATE OR REPLACE FUNCTION dml_utils_lib.calculate_migration_leaf(
    i_run_id bigint
)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run            dml_utils_data.migration_run%ROWTYPE;
    l_leaf           dml_utils_data.migration_run_leaf%ROWTYPE;
    l_key_columns    name[];
    l_key_kinds      text[];
    l_descriptor     record;
    l_caller         name;
    l_boundary_count bigint;
BEGIN
    IF i_run_id IS NULL THEN
        RAISE EXCEPTION 'run id must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- The run row lock serializes leaf calculation for this run: a second worker
    -- waits here, then sees the committed leaf state instead of appending the
    -- same leaf twice. The coordinator also holds the run-level advisory lock.
    SELECT *
    INTO l_run
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id
        FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    IF NOT l_run.partition_wise THEN
        RAISE EXCEPTION 'run % is not partition-wise', i_run_id
            USING ERRCODE = '22023';
    END IF;

    -- A finalized run has nothing left to calculate, and a cancelled run stops
    -- launching leaves. Both are a no-op so a resume can call this safely.
    IF l_run.boundaries_calculated_at IS NOT NULL OR l_run.cancelled_at IS NOT NULL THEN
        RETURN NULL;
    END IF;

    -- The first leaf that is not calculated, in manifest order. pending,
    -- calculating (a crashed claim) and failed are all retryable; calculated is
    -- never recomputed.
    SELECT *
    INTO l_leaf
    FROM dml_utils_data.migration_run_leaf AS leaf
    WHERE leaf.run_id = i_run_id
      AND leaf.state <> 'calculated'
    ORDER BY leaf.leaf_ordinal
        FOR UPDATE;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    -- The stored range is only valid for the relation it was built from; fail
    -- closed on any change before appending more boundaries.
    PERFORM dml_utils_lib.assert_migration_leaf_unchanged(
            i_run_id => i_run_id,
            i_leaf_ordinal => l_leaf.leaf_ordinal);

    -- This function is SECURITY DEFINER so the metadata writes and the leaf scan
    -- run as the owner. The scan must still be authorized by the caller, so
    -- recover the effective caller from SET ROLE and check the leaf, exactly as
    -- populate_migration_boundaries does for the driving table.
    l_caller := COALESCE(NULLIF(pg_catalog.current_setting('role', true), 'none'),
                         session_user)::name;
    IF NOT pg_catalog.has_table_privilege(
            l_caller,
            pg_catalog.format('%I.%I', l_leaf.leaf_schema_name, l_leaf.leaf_name),
            'SELECT')
    THEN
        RAISE EXCEPTION 'caller lacks SELECT privilege on leaf %.%',
            l_leaf.leaf_schema_name, l_leaf.leaf_name
            USING ERRCODE = '42501';
    END IF;

    -- Claim and calculation share the caller's transaction, so a crash leaves
    -- the leaf pending/failed rather than calculation without boundaries. A
    -- retried failed or stale-calculating leaf starts from an empty count.
    UPDATE dml_utils_data.migration_run_leaf
    SET state          = 'calculating',
        boundary_count = 0,
        calculated_at  = NULL,
        error_sqlstate = NULL,
        error_message  = NULL
    WHERE run_id = i_run_id
      AND leaf_ordinal = l_leaf.leaf_ordinal;

    IF l_run.chunk_by = 'blocks' THEN
        EXECUTE dml_utils_lib.build_block_leaf_boundary_population_sql(
                i_schema_name => l_leaf.leaf_schema_name,
                i_table_name => l_leaf.leaf_name,
                i_run_id => i_run_id,
                i_chunk_size => l_run.chunk_size,
                i_leaf_ordinal => l_leaf.leaf_ordinal);
    ELSE
        SELECT *
        INTO l_descriptor
        FROM dml_utils_lib.migration_run_boundary_key(i_run_id => i_run_id);
        l_key_columns := l_descriptor.o_key_columns;
        l_key_kinds := l_descriptor.o_key_kinds;

        EXECUTE dml_utils_lib.build_leaf_boundary_population_sql(
                i_schema_name => l_leaf.leaf_schema_name,
                i_table_name => l_leaf.leaf_name,
                i_key_columns => l_key_columns,
                i_key_kinds => l_key_kinds,
                i_run_id => i_run_id,
                i_chunk_size => l_run.chunk_size,
                i_leaf_ordinal => l_leaf.leaf_ordinal);
    END IF;

    SELECT pg_catalog.count(*)::bigint
    INTO l_boundary_count
    FROM dml_utils_data.migration_boundary AS boundary
    WHERE boundary.run_id = i_run_id
      AND boundary.leaf_ordinal = l_leaf.leaf_ordinal;

    UPDATE dml_utils_data.migration_run_leaf
    SET state          = 'calculated',
        boundary_count = l_boundary_count,
        calculated_at  = pg_catalog.clock_timestamp(),
        error_sqlstate = NULL,
        error_message  = NULL
    WHERE run_id = i_run_id
      AND leaf_ordinal = l_leaf.leaf_ordinal;

    RETURN l_leaf.leaf_ordinal;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.calculate_migration_leaf(bigint) IS
    'Calculates one partition-wise leaf in the caller''s transaction: claims the '
        'first pending/failed/stale-calculating leaf in manifest order, verifies '
        'it still matches the manifest, appends its chunk starts and marks it '
        'calculated with its boundary count. Returns the ordinal, or NULL when '
        'the run is finalized, cancelled or fully calculated. SECURITY DEFINER so '
        'the invoker-owned coordinator can write metadata and scan the leaf, with '
        'an explicit effective-caller SELECT check.';
