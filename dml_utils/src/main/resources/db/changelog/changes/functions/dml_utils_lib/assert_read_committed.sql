CREATE OR REPLACE FUNCTION dml_utils_lib.assert_read_committed()
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
    SET search_path = pg_catalog
AS
$$
BEGIN
    -- The runner observes autonomously committed state: create_migration_run and
    -- the partition-wise leaf calculation commit in pg_background workers, and
    -- the coordinator polls the run row to notice pause, cancel and completion.
    -- Every statement in READ COMMITTED takes a fresh snapshot, which is what
    -- makes those writes visible. REPEATABLE READ and SERIALIZABLE reuse one
    -- snapshot, so a worker's commit would stay invisible and the caller would
    -- see stale state (or fail to find a row that exists).
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'this routine requires READ COMMITTED isolation (the default); the current transaction is %',
            pg_catalog.current_setting('transaction_isolation')
            USING ERRCODE = '0A000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_read_committed IS
    'Raises feature_not_supported (0A000) when the current transaction is not '
        'READ COMMITTED. The runner routines call it because they observe state '
        'committed autonomously by pg_background workers; a REPEATABLE READ or '
        'SERIALIZABLE snapshot never sees that state.';
