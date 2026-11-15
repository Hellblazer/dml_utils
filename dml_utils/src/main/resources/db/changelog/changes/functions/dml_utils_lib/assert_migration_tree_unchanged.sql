CREATE OR REPLACE FUNCTION dml_utils_lib.assert_migration_tree_unchanged(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_partition_wise boolean;
    l_chunk_by       dml_utils_data.chunking_strategy;
    l_schema_name    text;
    l_table_name     text;
    l_mismatch       integer;
    l_reason         text;
BEGIN
    SELECT run.partition_wise,
           run.chunk_by,
           run.driving_table_schema_name::text,
           run.driving_table_name::text
    INTO l_partition_wise, l_chunk_by, l_schema_name, l_table_name
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- A single-relation run has no manifest to compare.
    IF NOT l_partition_wise THEN
        RETURN;
    END IF;

    -- Compare the live enumeration with the manifest in one set-based query: a
    -- missing/extra ordinal, or any changed identity/bound/filepath, is the first
    -- mismatching row.
    SELECT coalesce(live.leaf_ordinal, manifest.leaf_ordinal),
           CASE
               WHEN live.leaf_ordinal IS NULL
                   THEN pg_catalog.format('manifest leaf %s is missing from the live tree',
                                          manifest.leaf_ordinal)
               WHEN manifest.leaf_ordinal IS NULL
                   THEN pg_catalog.format('live leaf %s is missing from the manifest',
                                          live.leaf_ordinal)
               WHEN manifest.leaf_schema_name::text IS DISTINCT FROM live.leaf_schema_name
                   THEN pg_catalog.format('schema changed (stored %s, live %s)',
                                          manifest.leaf_schema_name, live.leaf_schema_name)
               WHEN manifest.leaf_name::text IS DISTINCT FROM live.leaf_name
                   THEN pg_catalog.format('name changed (stored %s, live %s)',
                                          manifest.leaf_name, live.leaf_name)
               WHEN manifest.leaf_oid IS DISTINCT FROM live.leaf_oid
                   THEN pg_catalog.format('oid changed (stored %s, live %s)',
                                          manifest.leaf_oid, live.leaf_oid)
               WHEN manifest.leaf_bound IS DISTINCT FROM live.leaf_bound
                   THEN pg_catalog.format('partition bound changed (stored %s, live %s)',
                                          manifest.leaf_bound, live.leaf_bound)
               WHEN l_chunk_by = 'blocks'
                   THEN pg_catalog.format('blocks filepath changed (stored %s, live %s)',
                                          manifest.relation_filepath, live.relation_filepath)
               ELSE 'leaf identity changed'
               END
    INTO l_mismatch, l_reason
    FROM dml_utils_lib.leaf_partitions(
                 i_schema_name => l_schema_name,
                 i_table_name => l_table_name,
                 i_chunk_by => l_chunk_by) AS live
             FULL OUTER JOIN (SELECT manifest_row.leaf_ordinal,
                                     manifest_row.leaf_schema_name,
                                     manifest_row.leaf_name,
                                     manifest_row.leaf_oid,
                                     manifest_row.leaf_bound,
                                     manifest_row.relation_filepath
                              FROM dml_utils_data.migration_run_leaf AS manifest_row
                              WHERE manifest_row.run_id = i_run_id) AS manifest
                             ON manifest.leaf_ordinal = live.leaf_ordinal
    WHERE live.leaf_ordinal IS NULL
       OR manifest.leaf_ordinal IS NULL
       OR manifest.leaf_schema_name::text IS DISTINCT FROM live.leaf_schema_name
       OR manifest.leaf_name::text IS DISTINCT FROM live.leaf_name
       OR manifest.leaf_oid IS DISTINCT FROM live.leaf_oid
       OR manifest.leaf_bound IS DISTINCT FROM live.leaf_bound
       OR (l_chunk_by = 'blocks'
        AND manifest.relation_filepath IS DISTINCT FROM live.relation_filepath)
    ORDER BY 1
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'partition tree changed for run %: leaf ordinal %: %',
            i_run_id, l_mismatch, l_reason
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_migration_tree_unchanged(dml_utils_data.non_null_bigint) IS
    'Re-enumerates a partition-wise run''s live leaf tree and compares it with the '
        'manifest (leaf count, order, schema/name/OID, partition bound and, for '
        'blocks, per-leaf filepath). Raises object_not_in_prerequisite_state (55000) on '
        'any difference so calculation and processing never mix stale and live '
        'leaves. A single-relation run is a no-op.';
