CREATE OR REPLACE FUNCTION dml_utils_lib.manifest_leaf_identity(
    i_run_id dml_utils_data.non_null_bigint,
    i_leaf_ordinal dml_utils_data.positive_integer,
    OUT o_chunk_by dml_utils_data.chunking_strategy,
    OUT o_leaf_schema_name text,
    OUT o_leaf_name text,
    OUT o_leaf_oid oid,
    OUT o_leaf_bound text,
    OUT o_relation_filepath text
)
    RETURNS record
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- Distinguish a missing run from a run that simply has no such leaf: the
    -- former is the same P0002 every other routine reports for an unknown run.
    IF NOT EXISTS (SELECT 1
                   FROM dml_utils_data.migration_run AS run
                   WHERE run.run_id = i_run_id)
    THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    SELECT run.chunk_by,
           leaf.leaf_schema_name::text,
           leaf.leaf_name::text,
           leaf.leaf_oid,
           leaf.leaf_bound,
           leaf.relation_filepath
    INTO o_chunk_by,
        o_leaf_schema_name,
        o_leaf_name,
        o_leaf_oid,
        o_leaf_bound,
        o_relation_filepath
    FROM dml_utils_data.migration_run AS run
             JOIN dml_utils_data.migration_run_leaf AS leaf ON leaf.run_id = run.run_id
    WHERE run.run_id = i_run_id
      AND leaf.leaf_ordinal = i_leaf_ordinal;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'run % has no manifest leaf %', i_run_id, i_leaf_ordinal
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.manifest_leaf_identity IS
    'Returns the stored identity of one partition-wise manifest leaf: chunk '
        'strategy, schema, name, OID, partition bound and blocks filepath. An '
        'unknown run is undefined_object (P0002); a known run without that leaf is '
        'object_not_in_prerequisite_state (55000).';
