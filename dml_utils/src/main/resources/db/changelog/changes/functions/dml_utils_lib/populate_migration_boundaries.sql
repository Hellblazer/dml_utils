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
    i_boundary_key_index name DEFAULT NULL
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

    -- A range run reads its key columns and kinds from the primary key or the
    -- named ordered-key index. A block run instead records the driving table's
    -- physical filepath so a resume can detect a heap rewrite, and the
    -- blocks<->filepath check on migration_run requires exactly one of the two.
    IF i_chunk_by = 'blocks' THEN
        PERFORM dml_utils_lib.assert_plain_heap(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
        l_relation_filepath := dml_utils_lib.relation_filepath(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
    ELSIF i_chunk_by = 'ordered_key' THEN
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
    ELSE
        l_key_columns := dml_utils_lib.primary_key_columns(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
        l_key_kinds := dml_utils_lib.primary_key_kinds(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
    END IF;

    -- started_at is the run's start: the boundary calculation begins here. It is
    -- written in this worker's transaction, so it commits with the run and the
    -- boundaries and survives a later failed chunk-processing attempt.
    -- The alias is a creation-time input, stored with the run so a resume
    -- renders with the same alias the first call used.
    INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size, chunk_by, threads,
                                              sleep_ms,
                                              driving_table_schema_name, driving_table_name,
                                              driving_table_alias,
                                              driving_table_relation_filepath,
                                              ordered_key_index, ordered_key_columns,
                                              ordered_key_kinds,
                                              started_at)
    VALUES (i_label, i_sql_text, i_chunk_size, i_chunk_by, i_threads, i_sleep_ms,
            i_schema_name, i_table_name,
            i_driving_table_alias,
            l_relation_filepath,
            CASE WHEN i_chunk_by = 'ordered_key' THEN i_boundary_key_index END,
            CASE WHEN i_chunk_by = 'ordered_key' THEN l_key_columns END,
            CASE WHEN i_chunk_by = 'ordered_key' THEN l_key_kinds END,
            pg_catalog.clock_timestamp())
    RETURNING run_id
        INTO l_run_id;

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
    'Creates a migration run for the label and populates its chunk boundaries for '
        'the given table and chunking strategy (primary_key, ordered_key or blocks), '
        'returning the new run_id. Stores the driving table alias and sleep_ms with '
        'the run at creation. Records the run''s started_at when the calculation '
        'begins and boundaries_calculated_at and total_chunks when it finishes, all '
        'in the worker''s transaction so they commit with the run; a block run also '
        'records the driving table''s physical filepath, and an ordered_key run '
        'records i_boundary_key_index and its resolved columns and kinds. '
        'Boundaries are written as the contiguous sequence 0..N in one statement; '
        'this is the only supported write path for boundaries.';
