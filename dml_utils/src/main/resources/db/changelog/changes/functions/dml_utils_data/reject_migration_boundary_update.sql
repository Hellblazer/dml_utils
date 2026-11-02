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

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils_data.reject_migration_boundary_update IS
    'BEFORE UPDATE trigger that rejects any change to '
        'migration_boundary.run_id, boundary_no, boundary_id or leaf_ordinal, '
        'and rejects rewriting a migration_boundary.cancelled_at that is already '
        'set.';
