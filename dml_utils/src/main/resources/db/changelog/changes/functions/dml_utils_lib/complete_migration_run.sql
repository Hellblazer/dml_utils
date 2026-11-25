CREATE OR REPLACE FUNCTION dml_utils_lib.complete_migration_run(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
BEGIN
    -- The coordinator calls this after every boundary is claimed. It is
    -- idempotent and leaves a cancelled run unchanged. The boundary calculation
    -- must be finished first: a partition-wise run is committed by phase 1 with
    -- started_at set, boundaries_calculated_at NULL and total_chunks 0, so
    -- without this guard total_chunks_completed >= total_chunks is 0 >= 0 and
    -- the run would be completed before a single boundary exists.
    UPDATE dml_utils_data.migration_run
    SET completed_at = pg_catalog.clock_timestamp(),
        paused_at    = NULL
    WHERE run_id = i_run_id
      AND completed_at IS NULL
      AND cancelled_at IS NULL
      AND boundaries_calculated_at IS NOT NULL
      AND total_chunks_completed >= total_chunks;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.complete_migration_run IS
    'Marks a run complete only when its boundaries are calculated and completed '
        'chunks cover total_chunks, clearing any pause in the same update. The run '
        'id parameter is a non-null domain. SECURITY DEFINER so the invoker-owned '
        'coordinator does not need metadata write privileges.';
