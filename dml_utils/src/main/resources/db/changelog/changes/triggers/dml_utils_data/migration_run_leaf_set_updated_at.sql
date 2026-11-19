CREATE OR REPLACE TRIGGER migration_run_leaf_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_run_leaf
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();

COMMENT ON TRIGGER migration_run_leaf_set_updated_at ON dml_utils_data.migration_run_leaf IS
    'Refreshes migration_run_leaf.updated_at on every update. An application '
        'caller cannot bypass it, but a table owner can DISABLE TRIGGER and a '
        'superuser (or a session with session_replication_role=replica) can '
        'bypass triggers, so it is not refreshed unconditionally.';
