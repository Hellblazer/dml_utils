CREATE OR REPLACE FUNCTION dml_utils_data.reject_migration_boundary_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- run_id, boundary_no, boundary_id and leaf_ordinal identify the boundary
    -- and the run/leaf whose key ordering and chunk ranges it belongs to. They
    -- are set when the boundaries are computed and must never change; moving a
    -- boundary to another run or leaf would let it process values computed for a
    -- different relation.
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
        OR NEW.boundary_no IS DISTINCT FROM OLD.boundary_no
        OR NEW.boundary_id IS DISTINCT FROM OLD.boundary_id
        OR NEW.leaf_ordinal IS DISTINCT FROM OLD.leaf_ordinal
    THEN
        RAISE EXCEPTION 'migration_boundary.run_id, boundary_no, boundary_id and leaf_ordinal are immutable'
            USING ERRCODE = '22023';
    END IF;

    -- cancelled_at is set once, by cancel_migration_run or the coordinator; a
    -- second cancellation is a no-op, and the timestamp must not be rewritten.
    IF OLD.cancelled_at IS NOT NULL
        AND NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at
    THEN
        RAISE EXCEPTION 'migration_boundary.cancelled_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- completed_at is written exactly once by complete_migration_boundary.
    -- Clearing or moving it would make the partial pending index and the next
    -- chunk lookup treat the boundary as pending again, processing the same rows
    -- twice while total_chunks_completed keeps its old value.
    IF OLD.completed_at IS NOT NULL
        AND NEW.completed_at IS DISTINCT FROM OLD.completed_at
    THEN
        RAISE EXCEPTION 'migration_boundary.completed_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- started_at is written by complete_migration_boundary in the same UPDATE
    -- that sets completed_at, and migration_boundaries.duration_ms is
    -- completed_at - started_at. Moving it after completion would silently
    -- rewrite the duration the run reports, so freeze it with completed_at.
    IF OLD.completed_at IS NOT NULL
        AND NEW.started_at IS DISTINCT FROM OLD.started_at
    THEN
        RAISE EXCEPTION 'migration_boundary.started_at is immutable once completed'
            USING ERRCODE = '22023';
    END IF;

    -- The two pairing checks below deliberately duplicate the nullability half of
    -- the table-level migration_boundary_rows_affected_check. The constraint is
    -- the only guard on INSERT; on UPDATE it would report a bare 23514, so these
    -- report the specific cause with 22023 and a message that names the column.
    -- Keep them in step with the constraint (and with the trigger comment).
    IF NEW.completed_at IS NULL AND NEW.rows_affected IS NOT NULL THEN
        RAISE EXCEPTION 'migration_boundary.rows_affected requires completed_at'
            USING ERRCODE = '22023';
    END IF;

    IF NEW.completed_at IS NOT NULL AND NEW.rows_affected IS NULL THEN
        RAISE EXCEPTION 'migration_boundary.rows_affected must be set with completed_at'
            USING ERRCODE = '22023';
    END IF;

    IF OLD.completed_at IS NOT NULL
        AND NEW.rows_affected IS DISTINCT FROM OLD.rows_affected
    THEN
        RAISE EXCEPTION 'migration_boundary.rows_affected is immutable once completed'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils_data.reject_migration_boundary_update IS
    'BEFORE UPDATE trigger that rejects any change to '
        'migration_boundary.run_id, boundary_no, boundary_id or leaf_ordinal, '
        'and rejects rewriting a migration_boundary.cancelled_at or '
        'migration_boundary.completed_at that is already set, or its started_at '
        'once completed_at is set (duration_ms is derived from the pair). It also '
        'requires rows_affected to be set exactly when completed_at is set, and '
        'rejects changing the count after completion.';
