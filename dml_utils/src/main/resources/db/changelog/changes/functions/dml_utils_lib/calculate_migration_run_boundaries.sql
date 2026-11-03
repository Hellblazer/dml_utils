CREATE OR REPLACE FUNCTION dml_utils_lib.calculate_migration_run_boundaries(
    i_run_id bigint
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_partition_wise boolean;
    l_cancelled_at   timestamptz;
    l_calculated_at  timestamptz;
    l_lock_key       bigint;
    l_handle         public.pg_background_handle;
    l_ordinal        integer;
    l_leaf_ordinal   integer;
    l_error_sqlstate text;
    l_error_message  text;
BEGIN
    IF i_run_id IS NULL THEN
        RAISE EXCEPTION 'run id must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    SELECT run.partition_wise, run.cancelled_at
    INTO l_partition_wise, l_cancelled_at
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;
    IF NOT l_partition_wise THEN
        RAISE EXCEPTION 'run % is not partition-wise', i_run_id
            USING ERRCODE = '22023';
    END IF;
    -- A cancelled run is terminal: do not take the lock or compare the tree.
    IF l_cancelled_at IS NOT NULL THEN
        RETURN;
    END IF;

    SELECT run.boundaries_calculated_at
    INTO l_calculated_at
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;
    IF l_calculated_at IS NOT NULL THEN
        RETURN;
    END IF;

    -- Serialize calculation for the run. The transaction-scoped advisory lock
    -- releases on both success and failure; a second coordinator that cannot take
    -- it fails closed instead of appending duplicate boundaries.
    l_lock_key := dml_utils_lib.migration_run_calculation_lock_key(i_run_id => i_run_id);
    IF NOT pg_catalog.pg_try_advisory_xact_lock(l_lock_key) THEN
        RAISE EXCEPTION 'boundary calculation for run % is already in progress', i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- Compare the live tree with the manifest once before starting. Each leaf is
    -- re-checked when it is claimed, and the tree is compared again before the
    -- terminal boundary, so a change cannot slip between leaves unnoticed.
    PERFORM dml_utils_lib.assert_migration_tree_unchanged(i_run_id => i_run_id);

    LOOP
        SELECT run.cancelled_at, run.boundaries_calculated_at
        INTO l_cancelled_at, l_calculated_at
        FROM dml_utils_data.migration_run AS run
        WHERE run.run_id = i_run_id;

        EXIT WHEN l_cancelled_at IS NOT NULL OR l_calculated_at IS NOT NULL;

        -- One leaf per worker transaction: calculate_migration_leaf claims the
        -- first pending/failed leaf, appends its starts and marks it calculated.
        -- A NULL result means there is no leaf left to calculate. The launch itself
        -- is outside the handler: an infrastructure failure there did not attempt
        -- the leaf, so it must not mark that leaf failed.
        l_handle := public.pg_background_launch(pg_catalog.format(
                'SELECT dml_utils_lib.calculate_migration_leaf(%s) AS ordinal',
                i_run_id));

        BEGIN
            PERFORM public.pg_background_wait(l_handle.pid, l_handle.cookie);
            SELECT ordinal
            INTO l_ordinal
            FROM public.pg_background_result(l_handle.pid, l_handle.cookie) AS (ordinal integer);
        EXCEPTION
            WHEN OTHERS THEN
                -- The leaf transaction rolled back, so the leaf is still pending and
                -- would be indistinguishable from one never attempted. Record the
                -- failure on the manifest in its own worker transaction, then
                -- re-raise the worker's error.
                l_error_sqlstate := SQLSTATE;
                l_error_message := SQLERRM;
                -- Derive the failed leaf now, after the worker transaction rolled
                -- back: the first non-calculated leaf is the one it claimed.
                SELECT leaf.leaf_ordinal
                INTO l_leaf_ordinal
                FROM dml_utils_data.migration_run_leaf AS leaf
                WHERE leaf.run_id = i_run_id
                  AND leaf.state <> 'calculated'
                ORDER BY leaf.leaf_ordinal
                LIMIT 1;
                IF FOUND THEN
                    BEGIN
                        PERFORM public.pg_background_run(pg_catalog.format(
                                'SELECT dml_utils_lib.record_migration_leaf_error(%s, %s, %L, %L)',
                                i_run_id, l_leaf_ordinal, l_error_sqlstate, l_error_message));
                    EXCEPTION
                        WHEN OTHERS THEN
                            NULL;
                    END;
                END IF;
                RAISE;
        END;

        EXIT WHEN l_ordinal IS NULL;
    END LOOP;

    SELECT run.cancelled_at, run.boundaries_calculated_at
    INTO l_cancelled_at, l_calculated_at
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    -- Cancellation stops launching leaves: the run is terminal and keeps the
    -- pending leaves for an operator to archive. A finalized run (for example by
    -- another coordinator) is done.
    IF l_cancelled_at IS NOT NULL OR l_calculated_at IS NOT NULL THEN
        RETURN;
    END IF;

    -- Re-check the tree before writing the terminal boundary, so a change during
    -- the loop cannot finalize stale ranges.
    PERFORM dml_utils_lib.assert_migration_tree_unchanged(i_run_id => i_run_id);

    -- Every leaf is calculated. Write the single terminal boundary and the run
    -- totals in their own worker transaction, so they commit autonomously.
    PERFORM public.pg_background_run(pg_catalog.format(
            'SELECT dml_utils_lib.finalize_migration_boundaries(%s)', i_run_id));
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.calculate_migration_run_boundaries(bigint) IS
    'Calculates a partition-wise run''s boundaries one leaf per pg_background '
        'transaction until every leaf is calculated, then finalizes the run in its '
        'own worker transaction. Serialized by a transaction-scoped advisory lock '
        'keyed by the run id, so a concurrent calculation fails closed with '
        'object_not_in_prerequisite_state (55000) instead of duplicating boundaries. '
        'The live tree is compared with the manifest before the loop and before '
        'finalization, and the claimed leaf is re-checked by calculate_migration_leaf. '
        'A failing leaf is recorded on the manifest (state = failed, SQLSTATE and '
        'message) in its own worker transaction before the error is re-raised. A '
        'cancelled run stops without finalizing, so its pending leaves stay untouched.';
