DROP FUNCTION IF EXISTS dml_utils.explain_function_over_table_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
);
