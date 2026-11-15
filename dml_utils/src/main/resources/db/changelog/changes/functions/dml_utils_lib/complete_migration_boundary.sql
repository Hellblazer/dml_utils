CREATE OR REPLACE FUNCTION dml_utils_lib.complete_migration_boundary(
    i_run_id dml_utils_data.non_null_bigint,
    i_boundary_no dml_utils_data.non_negative_bigint,
    i_started_at dml_utils_data.non_null_timestamptz,
    i_rows_affected dml_utils_data.non_negative_bigint
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_updated      integer;
    l_completed_at timestamptz;
BEGIN
    -- Use one timestamp for both columns and clamp a caller-supplied future
    -- start, so a clock-skewed caller cannot violate
    -- migration_boundary_time_order_check and abort an already-executed chunk.
    l_completed_at := pg_catalog.clock_timestamp();

    UPDATE dml_utils_data.migration_boundary
    SET started_at    = LEAST(i_started_at, l_completed_at),
        completed_at  = l_completed_at,
        rows_affected = i_rows_affected
    WHERE run_id = i_run_id
      AND boundary_no = i_boundary_no
      AND completed_at IS NULL
      AND cancelled_at IS NULL;

    GET DIAGNOSTICS l_updated = ROW_COUNT;
    IF l_updated = 0 THEN
        -- P0C02 is a completion-protocol failure, distinct from P0C01 (claim)
        -- and from P0002 raised inside user chunk SQL.
        RAISE EXCEPTION 'migration boundary % for run % was not found while completing it',
            i_boundary_no, i_run_id
            USING ERRCODE = 'P0C02';
    END IF;

    -- Keep both counters in the same transaction as the boundary completion so
    -- they cannot drift from the completed boundaries.
    UPDATE dml_utils_data.migration_run
    SET total_chunks_completed = total_chunks_completed + 1,
        total_rows_affected    = total_rows_affected + i_rows_affected
    WHERE run_id = i_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.complete_migration_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_timestamptz,
    dml_utils_data.non_negative_bigint
    ) IS
    'Records started_at/completed_at and rows_affected for one pending boundary '
        'and advances the run''s completed-chunk and total-rows-affected counters '
        'in the same transaction. The pending/cancelled guards make a second call '
        'a P0C02 error without double-incrementing the counters. SECURITY DEFINER '
        'because the caller-owned chunk worker intentionally has no metadata write '
        'privileges.';
