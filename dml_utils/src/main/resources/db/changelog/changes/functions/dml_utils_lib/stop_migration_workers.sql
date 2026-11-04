CREATE OR REPLACE FUNCTION dml_utils_lib.stop_migration_workers(
    i_pids integer[],
    i_cookies bigint[],
    i_cancel boolean,
    i_wait_ms integer
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
BEGIN
    IF i_pids IS NULL OR pg_catalog.cardinality(i_pids) = 0 THEN
        RETURN;
    END IF;

    -- Cancel every worker first, so a long-running chunk does not delay the
    -- cancellation of the ones behind it. A worker that already finished is a
    -- no-op for cancel; the wait/detach below reaps it.
    IF i_cancel THEN
        FOR l_index IN 1..pg_catalog.cardinality(i_pids)
            LOOP
                BEGIN
                    PERFORM public.pg_background_cancel(
                            i_pids[l_index], i_cookies[l_index], 1000);
                EXCEPTION
                    WHEN OTHERS THEN
                        NULL;
                END;
            END LOOP;
    END IF;

    -- Wait for each worker to stop and release its handle. A NULL wait timeout
    -- waits indefinitely; a cancelled chunk rolls back, leaving its boundary
    -- unclaimed.
    FOR l_index IN 1..pg_catalog.cardinality(i_pids)
        LOOP
            BEGIN
                IF i_wait_ms IS NULL THEN
                    PERFORM public.pg_background_wait(
                            i_pids[l_index], i_cookies[l_index]);
                ELSE
                    PERFORM public.pg_background_wait(
                            i_pids[l_index], i_cookies[l_index], i_wait_ms);
                END IF;
                PERFORM public.pg_background_detach(
                        i_pids[l_index], i_cookies[l_index]);
            EXCEPTION
                WHEN OTHERS THEN
                    NULL;
            END;
        END LOOP;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.stop_migration_workers(
    integer[],
    bigint[],
    boolean,
    integer
    ) IS
    'Cancels (when requested) and waits for/detaches a set of pg_background '
        'worker handles, given as parallel pid/cookie arrays. A NULL wait timeout '
        'waits indefinitely; per-worker errors are swallowed so the coordinator '
        'can finish its own error path.';
