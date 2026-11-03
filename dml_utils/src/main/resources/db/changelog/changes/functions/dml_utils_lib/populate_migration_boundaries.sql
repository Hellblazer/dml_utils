CREATE OR REPLACE FUNCTION dml_utils_lib.populate_migration_boundaries(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_sql_text dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer DEFAULT 1,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    i_driving_table_alias dml_utils_data.non_null_text DEFAULT 't',
    -- Delay in milliseconds after each completed chunk. 0 disables it.
    -- Negative values are rejected before any run is created.
    i_sleep_ms dml_utils_data.non_negative_integer DEFAULT 0,
    -- The unique index whose order an ordered_key run uses. Required for
    -- ordered_key and rejected for the other strategies.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, the driving table is a declarative partitioned parent and this
    -- call only creates the run and its pending leaf manifest; the per-leaf
    -- boundaries are calculated separately, one committed transaction per leaf.
    i_partition_wise dml_utils_data.non_null_boolean DEFAULT FALSE
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_key_columns         name[];
    l_key_kinds           text[];
    l_relation_filepath   text;
    l_run_id              bigint;
    l_caller              name;
    l_leaf_schema         text;
    l_leaf_name           text;
    l_ordered_key_index   name;
    l_ordered_key_columns name[];
    l_ordered_key_kinds   text[];
    l_parent_index        oid;
BEGIN
    -- The index selects the ordered key, so it is required for that strategy and
    -- rejected for the others; the shared helper owns that rule.
    PERFORM dml_utils_lib.assert_boundary_key_index(
            i_chunk_by => i_chunk_by,
            i_boundary_key_index => i_boundary_key_index);

    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    -- This function is SECURITY DEFINER so the boundary INSERT/UPDATE can run as
    -- the owner, but the driving-table scan must still be authorized by the
    -- caller. Recover the effective caller from SET ROLE and check its SELECT
    -- privilege before reading the table, so a caller cannot use the definer
    -- context to read a table it could not read directly.
    l_caller := COALESCE(NULLIF(pg_catalog.current_setting('role', true), 'none'),
                         session_user)::name;
    IF NOT pg_catalog.has_table_privilege(
            l_caller,
            pg_catalog.format('%I.%I', i_schema_name, i_table_name),
            'SELECT')
    THEN
        RAISE EXCEPTION 'caller lacks SELECT privilege on table %.%',
            i_schema_name, i_table_name
            USING ERRCODE = '42501';
    END IF;

    PERFORM dml_utils_lib.assert_no_active_run_for_label(i_label => i_label);

    -- Resolve and validate the key before the run exists. A partition-wise parent
    -- is resolved the same way; the per-leaf scan applies that key to each leaf.
    IF i_partition_wise::boolean THEN
        PERFORM dml_utils_lib.assert_partitioned_table(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
    END IF;

    -- A range run reads its key columns and kinds from the primary key or the
    -- named ordered-key index. A non-partition-wise block run instead records the
    -- driving table's physical filepath so a resume can detect a heap rewrite,
    -- and the blocks<->filepath check on migration_run requires exactly one of
    -- the two. A partition-wise block run records one filepath per leaf in the
    -- manifest, so the run-level filepath stays NULL.
    IF i_chunk_by = 'blocks' THEN
        IF NOT i_partition_wise::boolean THEN
            PERFORM dml_utils_lib.assert_plain_heap(
                    i_schema_name => i_schema_name,
                    i_table_name => i_table_name);
            l_relation_filepath := dml_utils_lib.relation_filepath(
                    i_schema_name => i_schema_name,
                    i_table_name => i_table_name);
        END IF;
    ELSIF i_chunk_by = 'ordered_key' THEN
        IF i_partition_wise::boolean THEN
            -- Validate the parent index shape and take its descriptor now. The
            -- leaf child indexes are checked against the manifest after it is
            -- inserted, so the validated set is exactly the stored set.
            l_parent_index := pg_catalog.to_regclass(
                    pg_catalog.format('%I.%I', i_schema_name, i_boundary_key_index));
            IF l_parent_index IS NULL THEN
                RAISE EXCEPTION 'index %.% does not exist on table %.%',
                    i_schema_name, i_boundary_key_index, i_schema_name, i_table_name
                    USING ERRCODE = '42704';
            END IF;

            SELECT pg_catalog.array_agg(a.column_name ORDER BY a.ordinality),
                   pg_catalog.array_agg(a.key_kind ORDER BY a.ordinality)
            INTO l_key_columns, l_key_kinds
            FROM dml_utils_lib.ordered_key_attributes_for_index(
                         i_index_oid => l_parent_index,
                         i_schema_name => i_schema_name,
                         i_table_name => i_table_name,
                         i_index_name => i_boundary_key_index) AS a;
        ELSE
            PERFORM dml_utils_lib.assert_usable_ordered_key(
                    i_schema_name => i_schema_name,
                    i_table_name => i_table_name,
                    i_index_name => i_boundary_key_index);
            l_key_columns := dml_utils_lib.ordered_key_columns(
                    i_schema_name => i_schema_name,
                    i_table_name => i_table_name,
                    i_index_name => i_boundary_key_index);
            l_key_kinds := dml_utils_lib.ordered_key_kinds(
                    i_schema_name => i_schema_name,
                    i_table_name => i_table_name,
                    i_index_name => i_boundary_key_index);
        END IF;
    ELSE
        l_key_columns := dml_utils_lib.primary_key_columns(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
        l_key_kinds := dml_utils_lib.primary_key_kinds(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
    END IF;

    -- The run-row constraint requires the ordered descriptor all-or-nothing; build
    -- the three values once so the INSERT has no mixed-type CASE expressions.
    IF i_chunk_by = 'ordered_key' THEN
        l_ordered_key_index := i_boundary_key_index;
        l_ordered_key_columns := l_key_columns;
        l_ordered_key_kinds := l_key_kinds;
    END IF;

    -- started_at is the run's start: the boundary calculation begins here. It is
    -- written in this worker's transaction, so it commits with the run and the
    -- boundaries and survives a later failed chunk-processing attempt.
    -- The alias is a creation-time input, stored with the run so a resume
    -- renders with the same alias the first call used.
    INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size, chunk_by,
                                              partition_wise, threads,
                                              sleep_ms,
                                              driving_table_schema_name, driving_table_name,
                                              driving_table_alias,
                                              driving_table_relation_filepath,
                                              ordered_key_index, ordered_key_columns,
                                              ordered_key_kinds,
                                              started_at)
    VALUES (i_label, i_sql_text, i_chunk_size, i_chunk_by, i_partition_wise::boolean, i_threads,
            i_sleep_ms,
            i_schema_name, i_table_name,
            i_driving_table_alias,
            l_relation_filepath,
            l_ordered_key_index,
            l_ordered_key_columns,
            l_ordered_key_kinds,
            pg_catalog.clock_timestamp())
    RETURNING run_id
        INTO l_run_id;

    IF i_partition_wise::boolean THEN
        -- Phase 1 only: snapshot the deterministic leaf manifest. The boundaries
        -- are calculated later, one leaf per committed transaction, so a failure
        -- preserves the leaves already done. Every leaf is inserted, including one
        -- with no rows; emptiness is a calculation result.

        -- One live enumeration feeds the snapshot. The caller's privilege is then
        -- checked against the inserted manifest (not a second enumeration, which
        -- could see a different tree); a denied leaf rolls the whole create back.
        INSERT INTO dml_utils_data.migration_run_leaf
        (run_id, leaf_ordinal, leaf_schema_name, leaf_name, leaf_oid,
         leaf_bound, relation_filepath, state)
        SELECT l_run_id,
               live.leaf_ordinal,
               live.leaf_schema_name,
               live.leaf_name,
               live.leaf_oid,
               live.leaf_bound,
               live.relation_filepath,
               'pending'
        FROM dml_utils_lib.leaf_partitions(
                     i_schema_name => i_schema_name,
                     i_table_name => i_table_name,
                     i_chunk_by => i_chunk_by) AS live;

        -- The manifest is now the exact leaf set; validate each stored leaf's
        -- ordered-key child index (a leaf attached between the descriptor read
        -- and the insert is not in this set, so it cannot slip through).
        IF i_chunk_by = 'ordered_key' THEN
            PERFORM dml_utils_lib.assert_partitioned_ordered_key_leaves(
                    i_run_id => l_run_id,
                    i_index_name => i_boundary_key_index);
        END IF;

        SELECT leaf.leaf_schema_name, leaf.leaf_name
        INTO l_leaf_schema, l_leaf_name
        FROM dml_utils_data.migration_run_leaf AS leaf
        WHERE leaf.run_id = l_run_id
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

        RETURN l_run_id;
    END IF;

    -- The boundary statement is built by a dml_utils_lib helper shared with the
    -- explain path, so both execute and explain the identical SQL. It is safe
    -- because every identifier comes from the catalog and every kind from the
    -- key-kind resolver whitelist; the run id and chunk size are inlined as
    -- bigints. Boundaries are written as the contiguous sequence 0..N in one
    -- statement, so a run has exactly the chunk starts plus one terminal
    -- boundary the runner assumes; this is the only supported write path.
    IF i_chunk_by = 'blocks' THEN
        EXECUTE dml_utils_lib.build_block_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_run_id => l_run_id,
                i_chunk_size => i_chunk_size::bigint);
    ELSE
        EXECUTE dml_utils_lib.build_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_key_columns => l_key_columns,
                i_key_kinds => l_key_kinds,
                i_run_id => l_run_id,
                i_chunk_size => i_chunk_size::bigint);
    END IF;

    -- The range calculation is done once the boundaries are inserted. Record it
    -- in this worker's transaction, so it commits with the run and is the
    -- milestone a resume relies on: the boundaries are never recomputed. The
    -- chunk count is the boundary count minus the terminal high-water boundary;
    -- an empty source has no boundaries, so it is clamped to 0.
    UPDATE dml_utils_data.migration_run
    SET boundaries_calculated_at = pg_catalog.clock_timestamp(),
        total_chunks             = (SELECT greatest(pg_catalog.count(*) - 1, 0::bigint)
                                    FROM dml_utils_data.migration_boundary
                                    WHERE run_id = l_run_id)
    WHERE run_id = l_run_id;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.populate_migration_boundaries IS
    'Creates a run and returns its id. For a single-relation run it scans the '
        'driving table in boundary-key order and inserts the contiguous 0..N '
        'boundaries in one statement. For i_partition_wise it instead validates the '
        'partitioned parent and every leaf, snapshots the deterministic leaf manifest '
        '(all leaves pending, with their OIDs, bounds and per-leaf block filepaths) '
        'and returns without boundaries or a boundaries_calculated_at milestone, so '
        'calculation can resume one leaf per transaction. SECURITY DEFINER so the '
        'metadata writes run as the owner, with an explicit effective-caller SELECT '
        'check on the parent and every leaf.';
