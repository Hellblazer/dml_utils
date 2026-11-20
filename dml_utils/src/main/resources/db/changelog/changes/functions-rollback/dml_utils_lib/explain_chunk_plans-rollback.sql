DROP FUNCTION IF EXISTS dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_element_name_array,
    dml_utils_data.non_null_element_text_array,
    dml_utils_data.positive_bigint,
    dml_utils_data.chunking_strategy
);
