CREATE OR REPLACE FUNCTION dml_utils_data.check_migration_run_leaf()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_partition_wise boolean;
    l_chunk_by       dml_utils_data.chunking_strategy;
    l_calculated_at  timestamptz;
    l_completed_at   timestamptz;
    l_cancelled_at   timestamptz;
    l_archived_at    timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Only an INSERT can add a leaf, so only it decides against the run's
        -- state. FOR SHARE serializes that decision with every other state change
        -- on the run row: finalize and cancel take FOR UPDATE, and a plain UPDATE
        -- takes FOR NO KEY UPDATE, so either they commit first (and this locking
        -- read re-reads the committed row) or they wait for this transaction.
        -- Without the lock the INSERT's later foreign-key check would only take
        -- FOR KEY SHARE, which conflicts with neither, so a leaf could be accepted
        -- after the run was closed.
        SELECT run.partition_wise,
               run.chunk_by,
               run.boundaries_calculated_at,
               run.completed_at,
               run.cancelled_at,
               run.archived_at
        INTO l_partition_wise, l_chunk_by, l_calculated_at, l_completed_at,
            l_cancelled_at, l_archived_at
        FROM dml_utils_data.migration_run AS run
        WHERE run.run_id = NEW.run_id
            FOR SHARE;
    ELSE
        -- An update only changes the calculation state, so it must not take a
        -- lock that would conflict with the run-row writers (the chunk counter,
        -- pause/resume, cancel, finalize) for the life of the leaf update.
        -- partition_wise and chunk_by are immutable, so an unlocked read is
        -- enough for the shape checks below.
        SELECT run.partition_wise,
               run.chunk_by
        INTO l_partition_wise, l_chunk_by
        FROM dml_utils_data.migration_run AS run
        WHERE run.run_id = NEW.run_id;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'run % does not exist', NEW.run_id
            USING ERRCODE = '23503';
    END IF;

    -- A manifest row only exists for a run that is chunked leaf by leaf.
    IF NOT l_partition_wise THEN
        RAISE EXCEPTION 'migration_run_leaf rows require a partition-wise run'
            USING ERRCODE = '22023';
    END IF;

    -- A finalized, completed, cancelled or archived run is closed: a leaf added
    -- afterwards could never be claimed (claim_next_migration_leaf returns for
    -- such a run), so it would stay pending forever and break the manifest/run
    -- state invariant. completed_at implies boundaries_calculated_at for any run
    -- this package finished, but a direct UPDATE can set the terminal stamps on
    -- their own, so every closed state is checked. The stamps are read only on
    -- INSERT above, so this is an insert-only rule.
    IF TG_OP = 'INSERT'
        AND (l_calculated_at IS NOT NULL
            OR l_cancelled_at IS NOT NULL
            OR l_completed_at IS NOT NULL
            OR l_archived_at IS NOT NULL)
    THEN
        RAISE EXCEPTION 'cannot add a leaf to a run that is finalized, completed, cancelled or archived: %',
            NEW.run_id
            USING ERRCODE = '55000';
    END IF;

    -- The per-leaf heap identity exists only for the blocks strategy.
    IF l_chunk_by = 'blocks' AND NEW.relation_filepath IS NULL THEN
        RAISE EXCEPTION 'a partition-wise blocks leaf must record its relation filepath'
            USING ERRCODE = '22023';
    END IF;
    IF l_chunk_by <> 'blocks' AND NEW.relation_filepath IS NOT NULL THEN
        RAISE EXCEPTION 'only a partition-wise blocks leaf records a relation filepath'
            USING ERRCODE = '22023';
    END IF;

    -- The identity snapshot is fixed when the manifest is created; only the
    -- calculation state, count, calculated_at and error fields may change.
    IF TG_OP = 'UPDATE' THEN
        IF NEW.run_id IS DISTINCT FROM OLD.run_id
            OR NEW.leaf_ordinal IS DISTINCT FROM OLD.leaf_ordinal
            OR NEW.leaf_schema_name IS DISTINCT FROM OLD.leaf_schema_name
            OR NEW.leaf_name IS DISTINCT FROM OLD.leaf_name
            OR NEW.leaf_oid IS DISTINCT FROM OLD.leaf_oid
            OR NEW.leaf_bound IS DISTINCT FROM OLD.leaf_bound
            OR NEW.relation_filepath IS DISTINCT FROM OLD.relation_filepath
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
        THEN
            RAISE EXCEPTION 'migration_run_leaf identity is immutable'
                USING ERRCODE = '22023';
        END IF;

        -- The high-water snapshot is written once, with the calculated state.
        IF OLD.terminal_boundary_id IS DISTINCT FROM NULL
            AND NEW.terminal_boundary_id IS DISTINCT FROM OLD.terminal_boundary_id
        THEN
            RAISE EXCEPTION 'migration_run_leaf terminal snapshot is immutable once set'
                USING ERRCODE = '22023';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils_data.check_migration_run_leaf IS
    'BEFORE INSERT OR UPDATE trigger that rejects a leaf whose run does not '
        'exist (23503) and requires a partition-wise run, rejects a leaf for a '
        'finalized, completed, cancelled or archived run (an insert only: it takes '
        'FOR SHARE on the run row, so a concurrent close is serialized with it), '
        'enforces the per-leaf relation-filepath rule for chunk_by = blocks, '
        'rejects changes to the manifest identity snapshot (including created_at) '
        'on update, and freezes terminal_boundary_id once set.';
