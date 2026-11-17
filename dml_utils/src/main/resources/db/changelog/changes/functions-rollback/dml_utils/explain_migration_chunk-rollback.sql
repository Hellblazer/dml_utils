DROP FUNCTION IF EXISTS dml_utils.explain_migration_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
);
