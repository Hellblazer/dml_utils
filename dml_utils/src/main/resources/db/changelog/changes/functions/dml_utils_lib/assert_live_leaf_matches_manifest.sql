CREATE OR REPLACE FUNCTION dml_utils_lib.assert_live_leaf_matches_manifest(
    i_chunk_by dml_utils_data.chunking_strategy,
    i_leaf_schema_name dml_utils_data.non_null_text,
    i_leaf_name dml_utils_data.non_null_text,
    i_leaf_oid dml_utils_data.non_null_oid,
    i_leaf_bound text,
    i_relation_filepath text
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
    -- The stored leaf_bound was deparsed by leaf_partitions under these pins;
    -- read the live bound the same way or a session in another time zone would
    -- report a spurious bound change.
    SET timezone = 'UTC'
    SET datestyle = 'ISO, MDY'
    SET intervalstyle = 'postgres'
    SET extra_float_digits = 0
AS
$$
DECLARE
    l_live_oid      oid;
    l_live_relkind  text;
    l_live_bound    text;
    l_live_filepath text;
BEGIN
    -- A NULL strategy would make both filepath guards below NULL and the function
    -- would fail open (a recorded filepath would go unverified). Manifest columns
    -- are NOT NULL, but the function is directly callable, so reject it.
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'chunk strategy must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- Hold the relation until the caller's transaction ends, before reading it: a
    -- catalog read alone is a point-in-time check, and DETACH, VACUUM FULL,
    -- CLUSTER or pg_repack could otherwise commit between the read and the scan
    -- that relies on it (the exact staleness this function exists to catch; for a
    -- blocks run it means ctid-addressed work on a rewritten heap). Taking the
    -- lock first also means the compared values are the ones the lock protects.
    -- ACCESS SHARE conflicts with the ACCESS EXCLUSIVE those operations need and
    -- with nothing the scan needs.
    BEGIN
        -- NOWAIT: waiting would block a claim for the whole duration of a rewrite,
        -- and a caller-side lock_timeout would surface 55P03 where this function
        -- documents 55000. Failing fast and translating both cases keeps the
        -- contract "the leaf is being changed" => 55000.
        EXECUTE pg_catalog.format('LOCK TABLE %I.%I IN ACCESS SHARE MODE NOWAIT',
                                  i_leaf_schema_name, i_leaf_name);
    EXCEPTION
        WHEN undefined_table OR invalid_schema_name THEN
            RAISE EXCEPTION 'leaf %.% no longer exists', i_leaf_schema_name, i_leaf_name
                USING ERRCODE = '55000';
        WHEN lock_not_available THEN
            RAISE EXCEPTION 'leaf %.% is being modified concurrently',
                i_leaf_schema_name, i_leaf_name
                USING ERRCODE = '55000';
        WHEN wrong_object_type THEN
            -- Not lockable as a table (an index); the relkind guard below reports
            -- it as the documented 55000.
            NULL;
    END;

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
    WHERE n.nspname = i_leaf_schema_name
      AND c.relname = i_leaf_name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leaf %.% no longer exists', i_leaf_schema_name, i_leaf_name
            USING ERRCODE = '55000';
    END IF;

    -- A dropped and recreated leaf or a rename keeps the name but changes the
    -- OID; a detach clears relpartbound. Either makes the stored ranges stale.
    IF l_live_oid IS DISTINCT FROM i_leaf_oid THEN
        RAISE EXCEPTION 'leaf %.% was replaced (stored oid %, live oid %)',
            i_leaf_schema_name, i_leaf_name, i_leaf_oid, l_live_oid
            USING ERRCODE = '55000';
    END IF;
    IF l_live_relkind <> 'r' THEN
        RAISE EXCEPTION 'leaf %.% is no longer an ordinary table (relkind %)',
            i_leaf_schema_name, i_leaf_name, l_live_relkind
            USING ERRCODE = '55000';
    END IF;
    IF l_live_bound IS DISTINCT FROM i_leaf_bound THEN
        RAISE EXCEPTION 'leaf %.% partition bound changed (stored %, live %)',
            i_leaf_schema_name, i_leaf_name, i_leaf_bound, l_live_bound
            USING ERRCODE = '55000';
    END IF;

    -- A blocks run addresses the heap by ctid, so a rewrite (VACUUM FULL,
    -- CLUSTER, pg_repack, tablespace move) invalidates its ranges. A key run
    -- never records a per-leaf filepath.
    IF i_chunk_by = 'blocks'
        AND l_live_filepath IS DISTINCT FROM i_relation_filepath
    THEN
        RAISE EXCEPTION 'leaf %.% heap changed (stored filepath %, live filepath %)',
            i_leaf_schema_name, i_leaf_name, i_relation_filepath, l_live_filepath
            USING ERRCODE = '55000';
    END IF;
    IF i_chunk_by <> 'blocks'
        AND i_relation_filepath IS NOT NULL
    THEN
        RAISE EXCEPTION 'leaf %.% records a filepath for a key-strategy run',
            i_leaf_schema_name, i_leaf_name
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_live_leaf_matches_manifest(
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_oid,
    text,
    text
    ) IS
    'Compares one manifest leaf identity with the live relation and raises '
        'object_not_in_prerequisite_state (55000) when it is missing, replaced '
        '(different OID), detached or rebound (different partition bound), not a '
        'plain table, or (for blocks) a rewritten heap. Takes ACCESS SHARE (NOWAIT) on '
        'the leaf so the verified identity cannot change before the caller uses it, '
        'and reports a concurrent modification as the same 55000.';
