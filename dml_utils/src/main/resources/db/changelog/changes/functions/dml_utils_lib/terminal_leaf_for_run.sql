CREATE OR REPLACE FUNCTION dml_utils_lib.terminal_leaf_for_run(
    i_run_id dml_utils_data.non_null_bigint,
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
    SET search_path = pg_catalog
AS
$$
BEGIN
    -- Distinguish an unknown run and a run that never snapshotted a manifest from
    -- the legitimate "every leaf is empty" result (all-NULL OUT fields). A
    -- zero-leaf parent has a snapshotted but empty manifest, so the marker, not
    -- the row count, decides.
    IF NOT EXISTS (SELECT 1
                   FROM dml_utils_data.migration_run AS run
                   WHERE run.run_id = i_run_id)
    THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;
    IF NOT EXISTS (SELECT 1
                   FROM dml_utils_data.migration_run AS run
                   WHERE run.run_id = i_run_id
                     AND run.manifest_snapshotted_at IS NOT NULL)
    THEN
        RAISE EXCEPTION 'run % has no leaf manifest', i_run_id
            USING ERRCODE = '55000';
    END IF;

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

COMMENT ON FUNCTION dml_utils_lib.terminal_leaf_for_run IS
    'Returns the last non-empty manifest leaf of a partition-wise run with its '
        'stored high-water key arrays, or NULL fields when every leaf is empty '
        '(including a parent with zero leaves). Raises P0002 for an unknown run and '
        '55000 for a run that never snapshotted a manifest, using '
        'migration_run.manifest_snapshotted_at so an empty run is not confused with '
        'a missing one. Used by finalize_migration_boundaries to choose the single '
        'terminal boundary.';
