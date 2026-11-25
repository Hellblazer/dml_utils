CREATE OR REPLACE TRIGGER migration_boundary_immutable
    BEFORE UPDATE
    ON dml_utils_data.migration_boundary
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.reject_migration_boundary_update();

COMMENT ON TRIGGER migration_boundary_immutable ON dml_utils_data.migration_boundary IS
    'Rejects any change to the columns that identify a computed boundary '
        '(run_id, boundary_no, boundary_id, leaf_ordinal); freezes cancelled_at and '
        'completed_at once set and started_at once the boundary is completed; '
        'requires rows_affected exactly when completed_at is set (both set or both '
        'NULL) and freezes the count after completion.';
