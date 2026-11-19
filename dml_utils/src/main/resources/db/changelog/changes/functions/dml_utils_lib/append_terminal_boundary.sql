CREATE OR REPLACE FUNCTION dml_utils_lib.append_terminal_boundary(
    i_run_id dml_utils_data.non_null_bigint,
    i_leaf_ordinal dml_utils_data.positive_integer
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run              record;
    l_leaf_schema_name text;
    l_leaf_name        text;
    l_terminal_key     dml_utils_data.migration_key;
    l_boundary_no      bigint;
BEGIN
    -- Serialize on the run row. max(boundary_no)+1 is only safe while one
    -- appender holds the lock, and the same lock makes the single-terminal check
    -- and the milestone write atomic against finalization and leaf calculation.
    SELECT run.partition_wise,
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

    -- This is the partition-wise finalization path. A single-relation run has no
    -- leaf manifest, so without this check the terminal-leaf lookup below would
    -- report the misleading "leaf ... is not the terminal leaf".
    IF NOT l_run.partition_wise THEN
        RAISE EXCEPTION 'run % is not partition-wise', i_run_id
            USING ERRCODE = '22023';
    END IF;

    IF l_run.cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION 'run % is cancelled', i_run_id
            USING ERRCODE = '55000';
    END IF;
    IF l_run.boundaries_calculated_at IS NOT NULL THEN
        -- Also true for an all-empty run, which finalize finalizes without a
        -- terminal boundary; the message must not claim a boundary exists.
        RAISE EXCEPTION 'run % boundaries are already calculated', i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- This function records the milestone itself, so it carries finalize's
    -- precondition too: a direct caller must not be able to finalize a run whose
    -- manifest still has pending or failed leaves (total_chunks would be short
    -- and the remaining leaves would never be calculated).
    IF EXISTS (SELECT 1
               FROM dml_utils_data.migration_run_leaf AS leaf
               WHERE leaf.run_id = i_run_id
                 AND leaf.state <> 'calculated')
    THEN
        RAISE EXCEPTION 'run % still has uncalculated leaves', i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- The ordinal is the only remaining caller-supplied input. It must be the run's
    -- last non-empty leaf: the runner ends the run's final chunk at this boundary,
    -- so finalizing with an earlier leaf would silently leave the later leaves
    -- unprocessed while the run looked complete.
    IF i_leaf_ordinal IS DISTINCT FROM (SELECT leaf.leaf_ordinal
                                        FROM dml_utils_data.migration_run_leaf AS leaf
                                        WHERE leaf.run_id = i_run_id
                                          AND leaf.boundary_count > 0
                                        ORDER BY leaf.leaf_ordinal DESC
                                        LIMIT 1)
    THEN
        RAISE EXCEPTION 'leaf % is not the terminal leaf of run %',
            i_leaf_ordinal, i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- The leaf identity comes from the manifest, never from the caller: the
    -- privilege check must authorize the leaf the row will actually be tagged
    -- with.
    SELECT identity.o_leaf_schema_name,
           identity.o_leaf_name
    INTO l_leaf_schema_name,
        l_leaf_name
    FROM dml_utils_lib.manifest_leaf_identity(
                 i_run_id => i_run_id,
                 i_leaf_ordinal => i_leaf_ordinal) AS identity;

    -- The terminal scan runs in the definer context, so the effective caller must
    -- still be allowed to read the leaf, as for leaf calculation.
    PERFORM dml_utils_lib.assert_caller_can_select_relation(
            i_schema_name => l_leaf_schema_name::dml_utils_data.non_null_text,
            i_table_name => l_leaf_name::dml_utils_data.non_null_text);

    -- The terminal key is the leaf's calculation-time high-water snapshot, also
    -- taken from the manifest: the runner uses it as the end of the leaf's last
    -- chunk, so a caller-supplied value that disagreed with the tagged leaf would
    -- silently mis-bound that chunk. NULL means the leaf was empty.
    -- Assigned from a scalar subquery: a composite `SELECT ... INTO` variable
    -- would go through the text I/O path and fail on the nested arrays.
    l_terminal_key := (SELECT leaf.terminal_boundary_id
                       FROM dml_utils_data.migration_run_leaf AS leaf
                       WHERE leaf.run_id = i_run_id
                         AND leaf.leaf_ordinal = i_leaf_ordinal);

    IF l_terminal_key IS NULL THEN
        RAISE EXCEPTION 'leaf % of run % has no terminal snapshot',
            i_leaf_ordinal, i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- Re-check the terminal leaf: the tree may have changed since its
    -- calculation (or since the last leaf committed).
    PERFORM dml_utils_lib.assert_migration_leaf_unchanged(
            i_run_id => i_run_id,
            i_leaf_ordinal => i_leaf_ordinal);

    SELECT coalesce(pg_catalog.max(boundary.boundary_no), -1) + 1
    INTO l_boundary_no
    FROM dml_utils_data.migration_boundary AS boundary
    WHERE boundary.run_id = i_run_id;

    INSERT INTO dml_utils_data.migration_boundary
        (run_id, boundary_no, boundary_id, leaf_ordinal, completed_at)
    VALUES (i_run_id, l_boundary_no, l_terminal_key, i_leaf_ordinal, NULL);

    -- The terminal is not a chunk, so the chunk count is its boundary_no. Record
    -- the milestone here as well: this function is then the single-terminal gate
    -- on its own, and a repeat call is rejected above before it can append a
    -- second terminal. finalize_migration_boundaries keeps its own milestone
    -- write for the all-empty-run case (where there is no terminal boundary).
    UPDATE dml_utils_data.migration_run
    SET boundaries_calculated_at = pg_catalog.clock_timestamp(),
        total_chunks             = l_boundary_no
    WHERE run_id = i_run_id
      AND boundaries_calculated_at IS NULL;

    RETURN l_boundary_no;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.append_terminal_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) IS
    'Appends the single terminal boundary for a partition-wise run after the '
        'last committed boundary and returns its boundary number (the run''s '
        'chunk count). A single-relation run is rejected with 22023. Every other '
        'input is taken from the manifest: the leaf identity and the caller''s '
        'SELECT privilege, and the terminal high-water key from '
        'migration_run_leaf. Serializes on the run row, records '
        'boundaries_calculated_at/total_chunks itself, and rejects a repeat '
        'append, a cancelled run, a run with uncalculated leaves, a non-terminal '
        'ordinal and a leaf without a snapshot (55000). A NULL run id or a NULL '
        'or non-positive ordinal is rejected by the parameter domains.';
