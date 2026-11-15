CREATE OR REPLACE FUNCTION dml_utils.migration_run_leaves(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS TABLE
            (
                run_id               bigint,
                leaf_ordinal         integer,
                leaf_schema_name     text,
                leaf_name            text,
                leaf_oid             oid,
                leaf_bound           text,
                relation_filepath    text,
                state                text,
                boundary_count       bigint,
                terminal_boundary_id dml_utils_data.migration_key,
                calculated_at        timestamptz,
                error_sqlstate       text,
                error_message        text,
                created_at           timestamptz,
                updated_at           timestamptz
            )
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
SELECT l.run_id,
       l.leaf_ordinal,
       l.leaf_schema_name::text,
       l.leaf_name::text,
       l.leaf_oid,
       l.leaf_bound,
       l.relation_filepath,
       l.state,
       l.boundary_count,
       l.terminal_boundary_id,
       l.calculated_at,
       l.error_sqlstate,
       l.error_message,
       l.created_at,
       l.updated_at
FROM dml_utils_data.migration_run_leaf AS l
WHERE l.run_id = i_run_id
ORDER BY l.leaf_ordinal;
$$;

COMMENT ON FUNCTION dml_utils.migration_run_leaves(dml_utils_data.non_null_bigint) IS
    'Returns one row per manifest leaf of a partition-wise run, in deterministic '
        'leaf order, with the leaf identity, calculation state, boundary count, '
        'high-water snapshot and any calculation error. A non-partition-wise run '
        'has no manifest and returns no rows.';
