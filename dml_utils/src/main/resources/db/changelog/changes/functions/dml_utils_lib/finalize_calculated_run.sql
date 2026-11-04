CREATE OR REPLACE FUNCTION dml_utils_lib.finalize_calculated_run(
    i_run_id bigint
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_cancelled_at  timestamptz;
    l_calculated_at timestamptz;
BEGIN
    SELECT run.cancelled_at, run.boundaries_calculated_at
    INTO l_cancelled_at, l_calculated_at
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    -- Cancellation stops finalization: the run is terminal and keeps the pending
    -- leaves for an operator to archive. A finalized run (for example by another
    -- coordinator) is done.
    IF l_cancelled_at IS NOT NULL OR l_calculated_at IS NOT NULL THEN
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

    -- Re-check the tree before writing the terminal boundary, so a change during
    -- the loop cannot finalize stale ranges.
    PERFORM dml_utils_lib.assert_migration_tree_unchanged(i_run_id => i_run_id);

    -- Every leaf is calculated. Write the single terminal boundary and the run
    -- totals in their own worker transaction, so they commit autonomously.
    PERFORM public.pg_background_run(pg_catalog.format(
            'SELECT dml_utils_lib.finalize_migration_boundaries(%s)', i_run_id));
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.finalize_calculated_run(bigint) IS
    'Finalizes a partition-wise run after every leaf is calculated: re-checks '
        'the live tree against the manifest and writes the terminal boundary and '
        'totals in an autonomous worker transaction. A cancelled or already '
        'finalized run is left untouched.';
