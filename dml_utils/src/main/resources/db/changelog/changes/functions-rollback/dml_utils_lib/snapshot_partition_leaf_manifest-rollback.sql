DROP FUNCTION IF EXISTS dml_utils_lib.snapshot_partition_leaf_manifest(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
);
