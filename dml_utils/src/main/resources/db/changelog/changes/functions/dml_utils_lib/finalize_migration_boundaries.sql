CREATE OR REPLACE FUNCTION dml_utils_lib.finalize_migration_boundaries(
    i_run_id bigint
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run          dml_utils_data.migration_run%ROWTYPE;
    l_leaf_ordinal integer;
    l_leaf_schema  text;
    l_leaf_name    text;
    l_caller       name;
    l_terminal_key dml_utils_data.migration_key;
    l_boundary_no  bigint;
    l_total_chunks bigint;
BEGIN
    IF i_run_id IS NULL THEN
        RAISE EXCEPTION 'run id must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- Serialize finalization with leaf calculation on the run row.
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

    -- Idempotent: a finalized run keeps its terminal boundary and totals.
    IF l_run.boundaries_calculated_at IS NOT NULL OR l_run.cancelled_at IS NOT NULL THEN
        RETURN;
    END IF;

    IF EXISTS (SELECT 1
               FROM dml_utils_data.migration_run_leaf AS leaf
               WHERE leaf.run_id = i_run_id
                 AND leaf.state <> 'calculated')
    THEN
        RAISE EXCEPTION 'run % still has uncalculated leaves', i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- The terminal belongs to the last non-empty leaf in manifest order. An
    -- empty leaf (boundary_count = 0) is calculated but has no chunk to end.
    SELECT leaf.leaf_ordinal, leaf.leaf_schema_name::text, leaf.leaf_name::text
    INTO l_leaf_ordinal, l_leaf_schema, l_leaf_name
    FROM dml_utils_data.migration_run_leaf AS leaf
    WHERE leaf.run_id = i_run_id
      AND leaf.boundary_count > 0
    ORDER BY leaf.leaf_ordinal DESC
    LIMIT 1;

    IF FOUND THEN
        -- The terminal scan runs in the definer context, so the effective caller
        -- must still be allowed to read the leaf, as for leaf calculation.
        l_caller := COALESCE(NULLIF(pg_catalog.current_setting('role', true), 'none'),
                             session_user)::name;
        IF NOT pg_catalog.has_table_privilege(
                l_caller,
                pg_catalog.format('%I.%I', l_leaf_schema, l_leaf_name),
                'SELECT')
        THEN
            RAISE EXCEPTION 'caller lacks SELECT privilege on leaf %.%',
                l_leaf_schema, l_leaf_name
                USING ERRCODE = '42501';
        END IF;

        -- Re-check the terminal leaf: the tree may have changed since its
        -- calculation (or since the last leaf committed).
        PERFORM dml_utils_lib.assert_migration_leaf_unchanged(
                i_run_id => i_run_id,
                i_leaf_ordinal => l_leaf_ordinal);

        l_terminal_key := dml_utils_lib.leaf_terminal_boundary_id(
                i_run_id => i_run_id,
                i_leaf_ordinal => l_leaf_ordinal);

        SELECT coalesce(max(boundary.boundary_no), -1) + 1
        INTO l_boundary_no
        FROM dml_utils_data.migration_boundary AS boundary
        WHERE boundary.run_id = i_run_id;

        INSERT INTO dml_utils_data.migration_boundary
            (run_id, boundary_no, boundary_id, leaf_ordinal, completed_at)
        VALUES (i_run_id, l_boundary_no, l_terminal_key, l_leaf_ordinal, NULL);

        -- The terminal is not a chunk, so the chunk count is its boundary_no.
        l_total_chunks := l_boundary_no;
    ELSE
        -- Every leaf is empty: no terminal boundary and no chunks.
        l_total_chunks := 0;
    END IF;

    UPDATE dml_utils_data.migration_run
    SET boundaries_calculated_at = pg_catalog.clock_timestamp(),
        total_chunks             = l_total_chunks
    WHERE run_id = i_run_id
      AND boundaries_calculated_at IS NULL;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.finalize_migration_boundaries(bigint) IS
    'Writes the single terminal boundary from the last non-empty leaf and sets '
        'boundaries_calculated_at and total_chunks, once. Idempotent (does nothing '
        'for a finalized or cancelled run) and raises 55000 while any leaf is not '
        'calculated. SECURITY DEFINER so the invoker-owned coordinator can write '
        'metadata and read the terminal leaf.';
