CREATE OR REPLACE FUNCTION dml_utils_lib.record_migration_worker_failure(
    i_run_id dml_utils_data.non_null_bigint,
    i_boundary_no dml_utils_data.non_negative_bigint,
    i_sqlstate text,
    i_error_message text
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_outcome record;
BEGIN
    -- Record the failure in its own worker/transaction, so the row commits
    -- autonomously and survives the coordinator's re-raise. Best effort: a
    -- recording failure must not mask the original worker error, but it must not
    -- be silent either. The worker outcome is inspected for exactly that reason.
    BEGIN
        SELECT outcome.completed,
               outcome.has_error,
               outcome.sqlstate,
               outcome.error_message
        INTO l_outcome
        FROM public.pg_background_run(pg_catalog.format(
                'SELECT dml_utils_lib.record_migration_error(%L, %L, %L, %L)',
                i_run_id,
                i_boundary_no,
                COALESCE(i_sqlstate, '55000'),
                COALESCE(i_error_message, 'chunk failed without an error message'))
             ) AS outcome;

        -- A worker that never ran its payload reports completed = false (and
        -- has_error = false); that is still a lost record.
        IF NOT COALESCE(l_outcome.completed, false)
            OR COALESCE(l_outcome.has_error, false)
        THEN
            RAISE WARNING
                'recording the failure of run % boundary % failed: % (%)',
                i_run_id, i_boundary_no, l_outcome.error_message, l_outcome.sqlstate;
        END IF;
    EXCEPTION
        WHEN OTHERS THEN
            RAISE WARNING
                'could not launch the failure-recording worker for run % boundary %: % (%)',
                i_run_id, i_boundary_no, SQLERRM, SQLSTATE;
    END;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.record_migration_worker_failure(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    text,
    text
    ) IS
    'Records a failed chunk in dml_utils_data.migration_error through an '
        'autonomous pg_background transaction. A NULL sqlstate or message is '
        'replaced with a generic value so the record is never lost. A recording '
        'failure is reported as a WARNING instead of being raised, so the caller '
        'can still re-raise the original worker error.';
