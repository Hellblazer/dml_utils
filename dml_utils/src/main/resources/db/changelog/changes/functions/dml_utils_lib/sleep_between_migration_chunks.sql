CREATE OR REPLACE FUNCTION dml_utils_lib.sleep_between_migration_chunks(
    i_run_id dml_utils_data.non_null_bigint,
    i_sleep_ms dml_utils_data.non_negative_integer
)
    RETURNS timestamptz
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    -- Cast to the base type: the domain -> integer assignment otherwise trips
    -- plpgsql_check's "target type is different type than source type".
    l_sleep_remaining_ms integer := i_sleep_ms::integer;
    l_sleep_slice_ms     integer;
    l_cancelled_at       timestamptz;
    l_paused_at          timestamptz;
BEGIN
    -- Slice the sleep so cancellation and pause are noticed promptly. sleep_ms
    -- itself is not re-read here: it is restart-only adjustment, stored with the
    -- run. The return value is cancelled_at; a pause also stops the sleep, but the
    -- caller re-reads the run state to see it.
    WHILE l_sleep_remaining_ms > 0
        LOOP
            l_sleep_slice_ms := least(l_sleep_remaining_ms, 100);
            PERFORM pg_catalog.pg_sleep(l_sleep_slice_ms::double precision / 1000.0);
            l_sleep_remaining_ms := l_sleep_remaining_ms - l_sleep_slice_ms;

            SELECT run.cancelled_at, run.paused_at
            INTO l_cancelled_at, l_paused_at
            FROM dml_utils_data.migration_run AS run
            WHERE run.run_id = i_run_id;

            -- The run row can be deleted (an archived terminal run) while the
            -- sleep is in progress. Stop rather than looping on the previous
            -- iteration's values; a missing row has nothing left to wait for.
            EXIT WHEN NOT FOUND;
            EXIT WHEN l_cancelled_at IS NOT NULL OR l_paused_at IS NOT NULL;
        END LOOP;

    RETURN l_cancelled_at;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.sleep_between_migration_chunks IS
    'Sleeps the run''s per-chunk delay in 100ms slices and returns the run''s '
        'cancelled_at if cancellation was noticed. A pause also interrupts the '
        'sleep; the caller re-reads the run state to see paused_at. A run row that '
        'disappears during the sleep ends it, so the caller stops instead of '
        'looping over a vanished run. A NULL or negative delay is rejected by the '
        'non-negative domain.';
