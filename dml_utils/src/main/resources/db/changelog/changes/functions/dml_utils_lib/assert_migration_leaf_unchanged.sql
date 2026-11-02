CREATE OR REPLACE FUNCTION dml_utils_lib.assert_migration_leaf_unchanged(
    i_run_id bigint,
    i_leaf_ordinal integer
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_chunk_by          dml_utils_data.chunking_strategy;
    l_schema_name       text;
    l_name              text;
    l_oid               oid;
    l_bound             text;
    l_relation_filepath text;
    l_live_oid          oid;
    l_live_relkind      text;
    l_live_bound        text;
    l_live_filepath     text;
BEGIN
    SELECT run.chunk_by,
           leaf.leaf_schema_name::text,
           leaf.leaf_name::text,
           leaf.leaf_oid,
           leaf.leaf_bound,
           leaf.relation_filepath
    INTO l_chunk_by,
        l_schema_name,
        l_name,
        l_oid,
        l_bound,
        l_relation_filepath
    FROM dml_utils_data.migration_run AS run
             JOIN dml_utils_data.migration_run_leaf AS leaf ON leaf.run_id = run.run_id
    WHERE run.run_id = i_run_id
      AND leaf.leaf_ordinal = i_leaf_ordinal;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leaf ordinal % does not belong to run %', i_leaf_ordinal, i_run_id
            USING ERRCODE = '55000';
    END IF;

    SELECT c.oid,
           c.relkind::text,
           pg_catalog.pg_get_expr(c.relpartbound, c.oid),
           pg_catalog.pg_relation_filepath(c.oid)
    INTO l_live_oid,
        l_live_relkind,
        l_live_bound,
        l_live_filepath
    FROM pg_catalog.pg_class AS c
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE n.nspname = l_schema_name
      AND c.relname = l_name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leaf %.% no longer exists', l_schema_name, l_name
            USING ERRCODE = '55000';
    END IF;

    -- A dropped and recreated leaf or a rename keeps the name but changes the
    -- OID; a detach clears relpartbound. Either makes the stored ranges stale.
    IF l_live_oid IS DISTINCT FROM l_oid THEN
        RAISE EXCEPTION 'leaf %.% was replaced (stored oid %, live oid %)',
            l_schema_name, l_name, l_oid, l_live_oid
            USING ERRCODE = '55000';
    END IF;
    IF l_live_relkind <> 'r' THEN
        RAISE EXCEPTION 'leaf %.% is no longer an ordinary table (relkind %)',
            l_schema_name, l_name, l_live_relkind
            USING ERRCODE = '55000';
    END IF;
    IF l_live_bound IS DISTINCT FROM l_bound THEN
        RAISE EXCEPTION 'leaf %.% partition bound changed (stored %, live %)',
            l_schema_name, l_name, l_bound, l_live_bound
            USING ERRCODE = '55000';
    END IF;

    -- A blocks run addresses the heap by ctid, so a rewrite (VACUUM FULL,
    -- CLUSTER, pg_repack, tablespace move) invalidates its ranges. A key run
    -- never records a per-leaf filepath.
    IF l_chunk_by = 'blocks'
        AND l_live_filepath IS DISTINCT FROM l_relation_filepath
    THEN
        RAISE EXCEPTION 'leaf %.% heap changed (stored filepath %, live filepath %)',
            l_schema_name, l_name, l_relation_filepath, l_live_filepath
            USING ERRCODE = '55000';
    END IF;
    IF l_chunk_by <> 'blocks' AND l_relation_filepath IS NOT NULL THEN
        RAISE EXCEPTION 'leaf %.% records a filepath for a key-strategy run',
            l_schema_name, l_name
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_migration_leaf_unchanged(
    bigint,
    integer
    ) IS
    'Raises invalid_transaction_initiation (55000) when a partition-wise run''s '
        'manifest leaf no longer matches the live relation: missing, replaced '
        '(different OID), detached or rebound (different partition bound), not a '
        'plain table, or (for blocks) a rewritten heap. Used before calculating a '
        'leaf and before processing it so stale ranges fail closed.';
