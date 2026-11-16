CREATE OR REPLACE TRIGGER migration_run_leaf_check
    BEFORE INSERT OR UPDATE
    ON dml_utils_data.migration_run_leaf
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.check_migration_run_leaf();

COMMENT ON TRIGGER migration_run_leaf_check ON dml_utils_data.migration_run_leaf IS
    'Requires a partition-wise run, enforces the per-leaf blocks filepath, '
        'rejects adding a leaf to a closed run (an insert, serialized by FOR SHARE '
        'on the run row), and rejects changes to the manifest identity snapshot.';
