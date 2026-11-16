CREATE OR REPLACE FUNCTION dml_utils.pause_migration_run(
    i_label dml_utils_data.non_null_text
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run_id    bigint;
    l_paused_at timestamptz;
BEGIN
    -- Lock the active run before deciding. The previous two-statement form left
    -- the no-op path unlocked, so a concurrent resume could commit between it and
    -- the fallback read and this call would then report no run at all. The row
    -- lock makes the counterpart wait and see this transaction's committed state.
    -- FOR NO KEY UPDATE is the weakest lock that still conflicts with the other
    -- run-row writers (their UPDATEs take FOR NO KEY UPDATE too), and it does not
    -- conflict with the FOR KEY SHARE lock a worker's boundary/error insert takes
    -- on this row through its foreign key.
    SELECT run.run_id, run.paused_at
    INTO l_run_id, l_paused_at
    FROM dml_utils_data.migration_run AS run
    WHERE run.label = i_label
      AND run.archived_at IS NULL
      AND run.completed_at IS NULL
      AND run.cancelled_at IS NULL
        FOR NO KEY UPDATE;

    -- Terminal or absent: there is no run to pause.
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    -- A second pause is idempotent and must not move the original timestamp.
    IF l_paused_at IS NULL THEN
        UPDATE dml_utils_data.migration_run
        SET paused_at = pg_catalog.clock_timestamp()
        WHERE run_id = l_run_id;
    END IF;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.pause_migration_run IS
    'Pauses the active (not archived, unfinished, not cancelled) migration run for '
        'the label, if any: sets paused_at so the coordinator stops launching new '
        'chunks and leaves the run resumable. The active run is read FOR NO KEY UPDATE, so '
        'a concurrent resume cannot commit between the decision and the result. A '
        'second pause returns the same run without moving the timestamp. Returns '
        'the paused run_id, or NULL when there was no active unfinished run. A '
        'paused run is not terminal; use resume_migration_run to continue or '
        'cancel_migration_run to stop it.';
