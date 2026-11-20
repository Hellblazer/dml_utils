CREATE OR REPLACE FUNCTION dml_utils_data.reject_migration_run_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- label, chunk_size, chunk_by, partition_wise, the key descriptors (the
    -- primary-key snapshot and the ordered-key index/descriptor) and the driving
    -- table (schema, name, alias and, for a block run, the relation filepath) are
    -- set when the run is created and must never change: together they identify
    -- the run, its rendered chunk SQL and the boundaries computed from it.
    IF NEW.label IS DISTINCT FROM OLD.label
        OR NEW.chunk_size IS DISTINCT FROM OLD.chunk_size
        OR NEW.chunk_by IS DISTINCT FROM OLD.chunk_by
        OR NEW.partition_wise IS DISTINCT FROM OLD.partition_wise
        OR NEW.ordered_key_index IS DISTINCT FROM OLD.ordered_key_index
        OR NEW.ordered_key_columns IS DISTINCT FROM OLD.ordered_key_columns
        OR NEW.ordered_key_kinds IS DISTINCT FROM OLD.ordered_key_kinds
        OR NEW.primary_key_columns IS DISTINCT FROM OLD.primary_key_columns
        OR NEW.primary_key_kinds IS DISTINCT FROM OLD.primary_key_kinds
        OR NEW.driving_table_schema_name IS DISTINCT FROM OLD.driving_table_schema_name
        OR NEW.driving_table_name IS DISTINCT FROM OLD.driving_table_name
        OR NEW.driving_table_alias IS DISTINCT FROM OLD.driving_table_alias
        OR NEW.driving_table_relation_filepath IS DISTINCT FROM OLD.driving_table_relation_filepath
    THEN
        RAISE EXCEPTION 'migration_run label, chunk_size, chunk_by, partition_wise, '
            'key descriptors and driving table are immutable'
            USING ERRCODE = '22023';
    END IF;

    -- started_at is the run's recorded start: boundary calculation sets it once,
    -- and every milestone-ordering CHECK and the progress rates depend on it.
    -- Clearing or moving it (in either direction) would falsify the run's
    -- timeline. Setting it from NULL is still allowed; that is how it is recorded.
    IF OLD.started_at IS NOT NULL
        AND NEW.started_at IS DISTINCT FROM OLD.started_at
    THEN
        RAISE EXCEPTION 'migration_run.started_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- total_chunks cannot be set at INSERT (the run row exists before its
    -- boundaries), so populate_migration_boundaries writes it in the same
    -- statement that sets boundaries_calculated_at. Freeze it from then on: the
    -- boundaries it counts are never recomputed, so a later change would
    -- invalidate the completed <= total invariant. total_chunks_completed stays
    -- mutable; the chunk workers advance it.
    IF NEW.total_chunks IS DISTINCT FROM OLD.total_chunks
        AND OLD.boundaries_calculated_at IS NOT NULL
    THEN
        RAISE EXCEPTION 'migration_run total_chunks is immutable once the boundaries are calculated'
            USING ERRCODE = '22023';
    END IF;

    -- manifest_snapshotted_at records that the leaf manifest was taken (even when
    -- it is empty); it is set once, by the snapshot, and never moves.
    IF OLD.manifest_snapshotted_at IS NOT NULL
        AND NEW.manifest_snapshotted_at IS DISTINCT FROM OLD.manifest_snapshotted_at
    THEN
        RAISE EXCEPTION 'migration_run.manifest_snapshotted_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- boundaries_calculated_at is the milestone the total_chunks freeze keys on;
    -- clearing or moving it would let a later statement rewrite total_chunks and
    -- break the completed <= total invariant.
    IF OLD.boundaries_calculated_at IS NOT NULL
        AND NEW.boundaries_calculated_at IS DISTINCT FROM OLD.boundaries_calculated_at
    THEN
        RAISE EXCEPTION 'migration_run.boundaries_calculated_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- paused_at is a reversible run-control flag, not an immutable identity. It
    -- may be set or cleared while the run is unfinished and not cancelled; a
    -- terminal run cannot be paused, and a non-NULL pause timestamp cannot be
    -- rewritten (a second pause is a no-op). Clearing it to NULL resumes.
    IF NEW.paused_at IS DISTINCT FROM OLD.paused_at THEN
        IF OLD.completed_at IS NOT NULL
            OR OLD.cancelled_at IS NOT NULL
            OR OLD.archived_at IS NOT NULL
        THEN
            RAISE EXCEPTION 'migration_run.paused_at cannot change once the run is terminal'
                USING ERRCODE = '22023';
        END IF;

        IF OLD.paused_at IS NOT NULL
            AND NEW.paused_at IS NOT NULL
        THEN
            RAISE EXCEPTION 'migration_run.paused_at is immutable once set; clear it to resume'
                USING ERRCODE = '22023';
        END IF;
    END IF;

    -- cancelled_at is set once, by cancel_migration_run or the coordinator; a
    -- second cancellation is a no-op, and the timestamp must not be rewritten.
    IF OLD.cancelled_at IS NOT NULL
        AND NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at
    THEN
        RAISE EXCEPTION 'migration_run.cancelled_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- completed_at is set once, by complete_migration_run. Clearing it would
    -- re-open a finished run, letting this trigger's paused_at guard be bypassed
    -- (clear completion, re-pause, restore it) and letting completion/cancellation
    -- run a second time with fresh timestamps.
    IF OLD.completed_at IS NOT NULL
        AND NEW.completed_at IS DISTINCT FROM OLD.completed_at
    THEN
        RAISE EXCEPTION 'migration_run.completed_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- archived_at is set once, by archive_migration_run. Clearing it would
    -- resurrect an archived run as active, so its label could be reused twice and
    -- the delete helper's terminal filter would no longer match it.
    IF OLD.archived_at IS NOT NULL
        AND NEW.archived_at IS DISTINCT FROM OLD.archived_at
    THEN
        RAISE EXCEPTION 'migration_run.archived_at is immutable once set'
            USING ERRCODE = '22023';
    END IF;

    -- paused_at is mutually exclusive with every terminal stamp. The table check
    -- constraint (migration_run_paused_at_check) covers completed_at,
    -- cancelled_at and archived_at; this is the backstop that rejects the
    -- archived case with 22023 instead of the constraint's 23514, consistent with
    -- the other pause rules. It matters because such a row is terminal (paused_at
    -- can never be cleared again) and can never be deleted: the delete helper needs
    -- completed_at or cancelled_at. An archived run that is not terminal stays
    -- allowed; the delete helper deliberately tolerates it.
    IF NEW.paused_at IS NOT NULL
        AND NEW.archived_at IS NOT NULL
    THEN
        RAISE EXCEPTION 'migration_run.paused_at must be NULL on an archived run'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils_data.reject_migration_run_update IS
    'BEFORE UPDATE trigger that rejects any change to migration_run.label, '
        'migration_run.chunk_size, migration_run.chunk_by, '
        'migration_run.partition_wise, the ordered-key descriptor '
        '(ordered_key_index, ordered_key_columns, ordered_key_kinds), the driving '
        'table schema/name/alias or the driving table relation filepath. It also '
        'rejects clearing or moving migration_run.started_at once set, rejects a '
        'change to migration_run.manifest_snapshotted_at once set, rejects a '
        'change to migration_run.total_chunks once '
        'boundaries_calculated_at is set (the value is written with that '
        'milestone), rejects clearing or moving boundaries_calculated_at once it '
        'is set, and rejects rewriting a migration_run.cancelled_at, '
        'migration_run.completed_at or migration_run.archived_at once set. It '
        'allows paused_at to be set or cleared on an unfinished, non-cancelled run, '
        'rejects pausing a terminal run, rejects a paused run that is also archived, '
        'and rejects rewriting a non-NULL paused_at; '
        'migration_run.total_chunks_completed stays mutable.';
