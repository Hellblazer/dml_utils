DROP FUNCTION IF EXISTS dml_utils.set_migration_run_retries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
);
