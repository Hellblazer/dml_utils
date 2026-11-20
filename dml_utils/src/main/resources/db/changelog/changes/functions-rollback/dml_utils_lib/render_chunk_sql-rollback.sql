DROP FUNCTION IF EXISTS dml_utils_lib.render_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_element_name_array,
    dml_utils_data.non_null_element_text_array,
    text[],
    text[],
    dml_utils_data.non_null_boolean
);
