CREATE OR REPLACE FUNCTION dml_utils_lib.snapshot_partition_leaf_manifest(
    i_run_id bigint,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy,
    i_boundary_key_index name
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_caller      name;
    l_leaf_schema text;
    l_leaf_name   text;
BEGIN
    l_caller := COALESCE(NULLIF(pg_catalog.current_setting('role', true), 'none'),
                         session_user)::name;

    -- Phase 1 only: snapshot the deterministic leaf manifest. The boundaries are
    -- calculated later, one leaf per committed transaction, so a failure
    -- preserves the leaves already done. Every leaf is inserted, including one
    -- with no rows; emptiness is a calculation result.
    INSERT INTO dml_utils_data.migration_run_leaf
    (run_id, leaf_ordinal, leaf_schema_name, leaf_name, leaf_oid,
     leaf_bound, relation_filepath, state)
    SELECT i_run_id,
           live.leaf_ordinal,
           live.leaf_schema_name::dml_utils_data.non_null_text,
           live.leaf_name::dml_utils_data.non_null_text,
           live.leaf_oid,
           live.leaf_bound,
           live.relation_filepath,
           'pending'
    FROM dml_utils_lib.leaf_partitions(
                 i_schema_name => i_schema_name,
                 i_table_name => i_table_name,
                 i_chunk_by => i_chunk_by) AS live;

    -- The manifest is now the exact leaf set; validate each stored leaf's
    -- ordered-key child index (a leaf attached between the descriptor read and
    -- the insert is not in this set, so it cannot slip through).
    IF i_chunk_by = 'ordered_key' THEN
        PERFORM dml_utils_lib.assert_partitioned_ordered_key_leaves(
                i_run_id => i_run_id,
                i_index_name => i_boundary_key_index);
    END IF;

    SELECT leaf.leaf_schema_name, leaf.leaf_name
    INTO l_leaf_schema, l_leaf_name
    FROM dml_utils_data.migration_run_leaf AS leaf
    WHERE leaf.run_id = i_run_id
      AND NOT pg_catalog.has_table_privilege(
            l_caller,
            pg_catalog.format('%I.%I', leaf.leaf_schema_name, leaf.leaf_name),
            'SELECT')
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'caller lacks SELECT privilege on leaf %.%',
            l_leaf_schema, l_leaf_name
            USING ERRCODE = '42501';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.snapshot_partition_leaf_manifest(
    bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) IS
    'Snapshots the deterministic leaf manifest for a new partition-wise run: one '
        'pending row per storage leaf with its identity, bound and blocks filepath, '
        'then validates every ordered-key child index and requires the effective '
        'caller to SELECT every stored leaf (42501). SECURITY DEFINER so the '
        'manifest write runs as the owner.';
