CREATE OR REPLACE FUNCTION dml_utils.archive_migration_run(
    i_label dml_utils_data.non_null_text
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run_id bigint;
BEGIN
    -- Lock the active run before doing anything else. The cancellation and the
    -- archive must target the same row: without the lock, a concurrent session
    -- can archive this run and create a fresh one with the same label between
    -- the two statements, and the UPDATE below would archive the fresh run
    -- without ever cancelling it. Holding the row lock also keeps the label
    -- pointing at this run until the transaction ends (a new run cannot be
    -- created while an active, non-archived run exists).
    SELECT run.run_id
    INTO l_run_id
    FROM dml_utils_data.migration_run AS run
    WHERE run.label = i_label
      AND run.archived_at IS NULL
        FOR UPDATE;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    -- An archived run is terminal, so cancel an unfinished one first. This also
    -- marks its unclaimed boundaries, and makes the delete helper's terminal
    -- filter an invariant rather than a way to strand abandoned runs forever.
    -- A run that is already cancelled or completed makes this a no-op.
    PERFORM dml_utils.cancel_migration_run(i_label => i_label);

    -- Archive by run_id, and use clock_timestamp (not now(), the transaction
    -- start): the cancellation above is stamped with clock_timestamp, so the
    -- archived timestamp must be later than it.
    UPDATE dml_utils_data.migration_run
    SET archived_at = pg_catalog.clock_timestamp()
    WHERE run_id = l_run_id
    RETURNING run_id
        INTO l_run_id;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.archive_migration_run IS
    'Archives the active (not archived) migration run for the label, if any, so '
        'the label can be reused; the active run row is locked first so the '
        'cancellation and the archive target the same run, and an unfinished run '
        'is cancelled before it is archived, so every archived run is terminal. '
        'Returns the archived run_id, or NULL when there was no active run.';
