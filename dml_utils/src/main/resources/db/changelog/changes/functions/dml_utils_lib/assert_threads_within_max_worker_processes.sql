CREATE OR REPLACE FUNCTION dml_utils_lib.assert_threads_within_max_worker_processes(
    i_threads dml_utils_data.positive_integer
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- max_worker_processes is the cluster-wide ceiling for background workers,
    -- not the free capacity for one call (other workers may hold slots). Both the
    -- runner (resolve_migration_run) and the setter (set_migration_run_threads)
    -- use this check so they cannot drift into different limits.
    IF i_threads::integer > pg_catalog.current_setting('max_worker_processes')::integer
    THEN
        RAISE EXCEPTION 'threads (%) exceeds max_worker_processes (%)',
            i_threads, pg_catalog.current_setting('max_worker_processes')
            USING ERRCODE = '22023';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_threads_within_max_worker_processes IS
    'Raises invalid_parameter_value (22023) when the requested worker count '
        'exceeds max_worker_processes. Shared by run_migration_chunks and '
        'set_migration_run_threads so the setter cannot accept a count the runner '
        'rejects.';
