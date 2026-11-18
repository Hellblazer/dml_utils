CREATE OR REPLACE TRIGGER migration_run_immutable
    BEFORE UPDATE
    ON dml_utils_data.migration_run
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.reject_migration_run_update();

COMMENT ON TRIGGER migration_run_immutable ON dml_utils_data.migration_run IS
    'Rejects updates to the migration_run identity fixed at creation (label, '
        'chunk size, chunking strategy, partition-wise flag, ordered-key '
        'descriptor, driving table schema/name/alias/filepath), freezes '
        'started_at once set, and freezes total_chunks and the milestones once '
        'the boundaries are calculated.';
