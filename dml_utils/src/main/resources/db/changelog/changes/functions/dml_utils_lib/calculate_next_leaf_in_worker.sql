CREATE OR REPLACE FUNCTION dml_utils_lib.calculate_next_leaf_in_worker(
    i_run_id bigint
)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_handle         public.pg_background_handle;
    l_ordinal        integer;
    l_leaf_ordinal   integer;
    l_error_sqlstate text;
    l_error_message  text;
BEGIN
    -- One leaf per worker transaction: calculate_migration_leaf claims the first
    -- pending/failed leaf, appends its starts and marks it calculated. A NULL
    -- result means there is no leaf left to calculate. The launch itself is
    -- outside the handler: an infrastructure failure there did not attempt the
    -- leaf, so it must not mark that leaf failed.
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

    RETURN l_ordinal;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.calculate_next_leaf_in_worker(bigint) IS
    'Runs one calculate_migration_leaf step in its own pg_background '
        'transaction and returns the calculated ordinal (NULL when no leaf is '
        'left). A failing leaf is recorded on the manifest in its own worker '
        'transaction before the worker error is re-raised.';
