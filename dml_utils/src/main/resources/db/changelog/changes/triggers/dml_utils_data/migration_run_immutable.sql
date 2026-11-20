CREATE OR REPLACE TRIGGER migration_run_immutable
    BEFORE UPDATE
    ON dml_utils_data.migration_run
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.reject_migration_run_update();

COMMENT ON TRIGGER migration_run_immutable ON dml_utils_data.migration_run IS
    'Rejects any change to the migration_run identity fixed at creation (label, '
        'chunk size, chunking strategy, partition-wise flag, key descriptors, '
        'driving table schema/name/alias/filepath); freezes started_at and '
        'manifest_snapshotted_at once set, total_chunks once the boundaries are '
        'calculated, and boundaries_calculated_at, completed_at, cancelled_at and '
        'archived_at once set. paused_at stays reversible while the run is '
        'unfinished (set once, cleared to resume, never rewritten), and cannot be '
        'set once the run is terminal or on an archived run.';
