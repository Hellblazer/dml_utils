CREATE OR REPLACE FUNCTION dml_utils.set_migration_run_retries(
    i_label dml_utils_data.non_null_text,
    i_max_retries dml_utils_data.non_negative_integer,
    i_retry_backoff_ms dml_utils_data.non_negative_integer
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
BEGIN
    -- Only an unfinished run can have its retry policy adjusted. This function
    -- only stores the policy; it takes effect when the coordinator that processes
    -- the run consumes it. A coordinator already processing keeps the values it
    -- read at its start, so adjust between calls.
    UPDATE dml_utils_data.migration_run
    SET max_retries      = i_max_retries,
        retry_backoff_ms = i_retry_backoff_ms
    WHERE label = i_label
      AND archived_at IS NULL
      AND completed_at IS NULL
      AND cancelled_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'no unfinished migration run for label %', i_label
            USING ERRCODE = 'P0002';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils.set_migration_run_retries IS
    'Replaces the recorded transient-retry policy of the unfinished run for the '
        'label: max_retries is the number of retries after the first attempt (0 '
        'disables retries) and retry_backoff_ms is the wait between attempts (0 '
        'retries immediately). This function only stores the policy; it takes '
        'effect when the coordinator that processes the run consumes it. A '
        'coordinator already processing keeps the values it read at its start, so '
        'adjust between calls.';
