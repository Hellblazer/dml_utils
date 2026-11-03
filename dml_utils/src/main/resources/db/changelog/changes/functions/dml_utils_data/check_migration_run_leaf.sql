CREATE OR REPLACE FUNCTION dml_utils_data.check_migration_run_leaf()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_partition_wise boolean;
    l_chunk_by       dml_utils_data.chunking_strategy;
BEGIN
    SELECT run.partition_wise, run.chunk_by
    INTO l_partition_wise, l_chunk_by
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = NEW.run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'run % does not exist', NEW.run_id
            USING ERRCODE = '23503';
    END IF;

    -- A manifest row only exists for a run that is chunked leaf by leaf.
    IF NOT l_partition_wise THEN
        RAISE EXCEPTION 'migration_run_leaf rows require a partition-wise run'
            USING ERRCODE = '22023';
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
    'BEFORE INSERT OR UPDATE trigger that requires a partition-wise run, '
        'enforces the per-leaf relation-filepath rule for chunk_by = blocks, '
        'rejects changes to the manifest identity snapshot on update, and freezes '
        'the high-water snapshot once set.';
