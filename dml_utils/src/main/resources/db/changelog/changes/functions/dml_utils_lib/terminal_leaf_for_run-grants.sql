REVOKE EXECUTE ON FUNCTION dml_utils_lib.terminal_leaf_for_run(
    dml_utils_data.non_null_bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.terminal_leaf_for_run(
    dml_utils_data.non_null_bigint
    ) TO dml_utils_caller;
