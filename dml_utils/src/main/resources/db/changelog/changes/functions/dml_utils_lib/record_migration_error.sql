CREATE OR REPLACE FUNCTION dml_utils_lib.record_migration_error(
    i_run_id dml_utils_data.non_null_bigint,
    i_boundary_no dml_utils_data.non_negative_bigint,
    i_sqlstate dml_utils_data.non_null_text,
    i_message dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
BEGIN
    INSERT INTO dml_utils_data.migration_error (run_id, boundary_no, sqlstate, message)
    VALUES (i_run_id, i_boundary_no, i_sqlstate, i_message);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.record_migration_error IS
    'Records one failed chunk worker for a run and boundary; intended to run in a '
        'pg_background worker so the row commits autonomously. SECURITY DEFINER '
        'with a pinned search_path so a caller holding only EXECUTE on the '
        'metadata helpers (no direct INSERT on migration_error) can record the '
        'failure; the insert is parameterized and writes only the caller-supplied '
        'values, and EXECUTE is granted explicitly with PUBLIC revoked.';
