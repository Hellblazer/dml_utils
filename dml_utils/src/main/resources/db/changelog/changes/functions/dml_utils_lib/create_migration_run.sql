CREATE OR REPLACE FUNCTION dml_utils_lib.create_migration_run(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_sql_text dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer,
    i_chunk_by dml_utils_data.chunking_strategy,
    i_driving_table_alias dml_utils_data.non_null_text,
    i_sleep_ms dml_utils_data.non_negative_integer,
    i_boundary_key_index name,
    i_partition_wise dml_utils_data.non_null_boolean,
    i_max_retries dml_utils_data.non_negative_integer DEFAULT 3,
    i_retry_backoff_ms dml_utils_data.non_negative_integer DEFAULT 1000
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_handle        public.pg_background_handle;
    l_worker_run_id bigint;
BEGIN
    -- Match the run-table constraint locally: an ordered_key run needs the index
    -- and the other strategies must not carry one. Rejecting here names the
    -- parameter instead of surfacing the error from the worker.
    PERFORM dml_utils_lib.assert_boundary_key_index(
            i_chunk_by => i_chunk_by,
            i_boundary_key_index => i_boundary_key_index);

    -- Populate the boundaries in a worker so the run and its boundaries commit
    -- autonomously and become visible to the processing workers.
    l_handle := public.pg_background_launch(pg_catalog.format(
            'SELECT dml_utils_lib.populate_migration_boundaries(
    %L,
    %L,
    %L,
    %L,
    %s,
    %s,
    %L,
    %L,
    %s,
    %L,
    %L,
    %s,
    %s
    ) AS run_id',
            i_schema_name,
            i_table_name,
            i_label,
            i_sql_text,
            i_chunk_size,
            i_threads,
            i_chunk_by::text,
            i_driving_table_alias,
            i_sleep_ms,
            i_boundary_key_index,
            i_partition_wise::boolean,
            i_max_retries,
            i_retry_backoff_ms));
    BEGIN
        PERFORM public.pg_background_wait(l_handle.pid, l_handle.cookie);
    EXCEPTION
        -- QUERY_CANCELED (57014: statement_timeout or a cancel request) is not
        -- caught by WHEN OTHERS, so name it explicitly.
        WHEN QUERY_CANCELED OR OTHERS THEN
            -- The waiter was aborted (statement_timeout, query cancel) while the
            -- worker may still be running. pg_background has no abort callback,
            -- so cancel the worker before re-raising: otherwise it can still
            -- commit the run row after the caller's statement failed, leaving an
            -- orphan run that holds the label. Best-effort both ways — the worker
            -- may already have committed, and the handler must not mask the
            -- original error — then detach so the handle is not leaked.
            BEGIN
                PERFORM public.pg_background_cancel(
                        l_handle.pid, l_handle.cookie, 1000);
            EXCEPTION
                WHEN OTHERS THEN
                    NULL;
            END;
            BEGIN
                PERFORM public.pg_background_detach(
                        l_handle.pid, l_handle.cookie);
            EXCEPTION
                WHEN OTHERS THEN
                    NULL;
            END;
            RAISE;
    END;

    -- result() is one-time consumption and auto-detaches; it re-raises the
    -- worker's SQLSTATE (for example 23505 on a concurrent same-label run). The
    -- worker has finished, so only the handle can leak here.
    BEGIN
        SELECT run_id
        INTO l_worker_run_id
        FROM public.pg_background_result(l_handle.pid, l_handle.cookie) AS (run_id bigint);
    EXCEPTION
        WHEN OTHERS THEN
            BEGIN
                PERFORM public.pg_background_detach(
                        l_handle.pid, l_handle.cookie);
            EXCEPTION
                WHEN OTHERS THEN
                    NULL;
            END;
            RAISE;
    END;

    RETURN l_worker_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.create_migration_run(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) IS
    'Creates a new run by calling populate_migration_boundaries in a pg_background '
        'worker, so the run and its boundaries commit autonomously, and returns '
        'the run id. Re-raises the worker''s SQLSTATE (for example a concurrent '
        'same-label 23505). If the wait itself is aborted (QUERY_CANCELED, which '
        'WHEN OTHERS does not catch, or another error), the worker is cancelled '
        'and the handle detached best-effort before the original error is '
        're-raised, so the worker does not commit a run the caller never '
        'received (a worker that already committed cannot be recalled).';
