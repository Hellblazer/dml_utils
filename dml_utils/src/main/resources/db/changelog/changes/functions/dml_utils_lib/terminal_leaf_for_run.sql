CREATE OR REPLACE FUNCTION dml_utils_lib.terminal_leaf_for_run(
    i_run_id bigint,
    OUT o_leaf_ordinal integer,
    OUT o_leaf_schema_name text,
    OUT o_leaf_name text,
    OUT o_bigint_values bigint[],
    OUT o_text_values text[],
    OUT o_uuid_values uuid[],
    OUT o_timestamptz_values timestamptz[]
)
    RETURNS record
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- The terminal belongs to the last non-empty leaf in manifest order. An
    -- empty leaf (boundary_count = 0) is calculated but has no chunk to end.
    SELECT leaf.leaf_ordinal,
           leaf.leaf_schema_name::text,
           leaf.leaf_name::text,
           (leaf.terminal_boundary_id).bigint_values,
           (leaf.terminal_boundary_id).text_values,
           (leaf.terminal_boundary_id).uuid_values,
           (leaf.terminal_boundary_id).timestamptz_values
    INTO o_leaf_ordinal, o_leaf_schema_name, o_leaf_name,
        o_bigint_values, o_text_values, o_uuid_values, o_timestamptz_values
    FROM dml_utils_data.migration_run_leaf AS leaf
    WHERE leaf.run_id = i_run_id
      AND leaf.boundary_count > 0
    ORDER BY leaf.leaf_ordinal DESC
    LIMIT 1;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.terminal_leaf_for_run(bigint) IS
    'Returns the last non-empty manifest leaf of a partition-wise run with its '
        'stored high-water key arrays, or NULL fields when every leaf is empty. '
        'Used by finalize_migration_boundaries to choose the single terminal '
        'boundary.';
