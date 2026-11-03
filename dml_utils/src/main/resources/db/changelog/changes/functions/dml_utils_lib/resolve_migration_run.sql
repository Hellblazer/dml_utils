CREATE OR REPLACE FUNCTION dml_utils_lib.resolve_migration_run(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer,
    i_driving_table_alias dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- Initial delay for a new run; a resumed run uses its stored value.
    i_sleep_ms dml_utils_data.non_negative_integer DEFAULT 0,
    -- The unique index whose order an ordered_key run uses. Required for a new
    -- ordered_key run; ignored with a notice when resuming an existing run, where
    -- the stored strategy and index win.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, the driving table is a declarative partitioned parent and the
    -- run's boundaries are calculated leaf by leaf. The stored flag wins on resume.
    i_partition_wise dml_utils_data.non_null_boolean DEFAULT FALSE,
    OUT o_run_id bigint,
    OUT o_already_completed boolean,
    OUT o_effective_sql_text text,
    OUT o_effective_schema_name text,
    OUT o_effective_table_name text,
    OUT o_effective_alias text,
    OUT o_effective_threads integer,
    OUT o_effective_sleep_ms integer,
    OUT o_chunk_by dml_utils_data.chunking_strategy,
    OUT o_partition_wise boolean,
    OUT o_key_columns name[],
    OUT o_key_kinds text[]
)
    RETURNS record
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_completed_at               timestamptz;
    l_stored_sql_text            text;
    l_stored_chunk_size          integer;
    l_stored_chunk_by            dml_utils_data.chunking_strategy;
    l_stored_partition_wise      boolean;
    l_stored_threads             integer;
    l_stored_sleep_ms            integer;
    l_stored_schema_name         text;
    l_stored_table_name          text;
    l_stored_alias               text;
    l_stored_filepath            text;
    l_stored_ordered_key_index   name;
    l_stored_ordered_key_columns name[];
    l_stored_ordered_key_kinds   text[];
    l_handle                     public.pg_background_handle;
    l_worker_run_id              bigint;
    l_live_columns               name[];
    l_live_kinds                 text[];
BEGIN
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- Reuse the active run for the label when there is one; otherwise create it
    -- by running populate_migration_boundaries in a worker so it commits
    -- autonomously and the boundaries become visible to the processing workers.
    SELECT run_id,
           completed_at,
           sql_text,
           chunk_size,
           chunk_by,
           partition_wise,
           threads::integer,
           sleep_ms::integer,
           driving_table_schema_name::text,
           driving_table_name::text,
           driving_table_alias::text,
           driving_table_relation_filepath,
           ordered_key_index,
           ordered_key_columns,
           ordered_key_kinds
    INTO o_run_id, l_completed_at, l_stored_sql_text, l_stored_chunk_size,
        l_stored_chunk_by, l_stored_partition_wise, l_stored_threads, l_stored_sleep_ms,
        l_stored_schema_name,
        l_stored_table_name, l_stored_alias, l_stored_filepath,
        l_stored_ordered_key_index, l_stored_ordered_key_columns,
        l_stored_ordered_key_kinds
    FROM dml_utils_data.migration_run
    WHERE label = i_label
      AND archived_at IS NULL;

    IF FOUND AND l_completed_at IS NOT NULL THEN
        RAISE NOTICE 'run for label % already completed at %', i_label, l_completed_at;
        o_already_completed := true;
        RETURN;
    END IF;

    -- The stored thread count wins for a resumed run. Each chunk is one
    -- background worker; max_worker_processes is a cluster-wide ceiling, not the
    -- free capacity for this call (other workers may hold slots), so this is a
    -- cheap early sanity check, not a capacity guarantee. A launch that still
    -- fails partway leaves the launched chunks committed and the run resumable,
    -- which the caller retries.
    o_effective_threads := CASE WHEN FOUND THEN l_stored_threads ELSE i_threads::integer END;
    o_effective_sleep_ms := CASE WHEN FOUND THEN l_stored_sleep_ms ELSE i_sleep_ms::integer END;
    IF o_effective_threads > pg_catalog.current_setting('max_worker_processes')::integer THEN
        RAISE EXCEPTION 'threads (%) exceeds max_worker_processes (%)',
            o_effective_threads, pg_catalog.current_setting('max_worker_processes')
            USING ERRCODE = '22023';
    END IF;

    IF FOUND THEN
        -- A resumed run uses the SQL, chunk size, strategy, threads and driving
        -- table recorded when it was created, so an adjusted statement (for
        -- example to fix a bad execution plan) is applied via
        -- set_migration_run_sql_text rather than a changed call. A differing
        -- input is ignored, with a notice, so the boundaries and their chunk SQL
        -- are never silently redefined.
        o_effective_sql_text := l_stored_sql_text;
        o_effective_schema_name := l_stored_schema_name;
        o_effective_table_name := l_stored_table_name;
        o_effective_alias := l_stored_alias;
        o_chunk_by := l_stored_chunk_by;
        o_partition_wise := l_stored_partition_wise;
        IF l_stored_chunk_by IS DISTINCT FROM i_chunk_by THEN
            RAISE NOTICE 'run for label % already exists; using the stored chunk_by %',
                i_label, l_stored_chunk_by;
        END IF;
        IF l_stored_partition_wise IS DISTINCT FROM i_partition_wise::boolean THEN
            RAISE NOTICE 'run for label % already exists; using the stored partition_wise %',
                i_label, l_stored_partition_wise;
        END IF;
        -- The stored strategy and descriptor win on resume, like the other stored
        -- inputs. The boundaries were built from the stored index, so fail closed
        -- if it is no longer usable or resolves to a different descriptor: a
        -- dropped, invalid, partial, reordered or differently-typed index would
        -- otherwise let the run continue over a key it no longer matches.
        IF l_stored_chunk_by = 'ordered_key' THEN
            IF l_stored_ordered_key_index IS DISTINCT FROM i_boundary_key_index THEN
                RAISE NOTICE 'run for label % already exists; using the stored ordered_key_index %',
                    i_label, l_stored_ordered_key_index;
            END IF;

            BEGIN
                -- One traversal validates the stored index (and, for a
                -- partition-wise run, every leaf child index) and yields the live
                -- descriptor, which must equal the stored one.
                IF l_stored_partition_wise THEN
                    SELECT pg_catalog.array_agg(a.column_name ORDER BY a.ordinality),
                           pg_catalog.array_agg(a.key_kind ORDER BY a.ordinality)
                    INTO l_live_columns, l_live_kinds
                    FROM dml_utils_lib.partitioned_ordered_key_attributes(
                                 i_schema_name => o_effective_schema_name,
                                 i_table_name => o_effective_table_name,
                                 i_index_name => l_stored_ordered_key_index) AS a;
                ELSE
                    l_live_columns := dml_utils_lib.ordered_key_columns(
                            i_schema_name => o_effective_schema_name,
                            i_table_name => o_effective_table_name,
                            i_index_name => l_stored_ordered_key_index);
                    l_live_kinds := dml_utils_lib.ordered_key_kinds(
                            i_schema_name => o_effective_schema_name,
                            i_table_name => o_effective_table_name,
                            i_index_name => l_stored_ordered_key_index);
                END IF;

                IF l_live_columns IS DISTINCT FROM l_stored_ordered_key_columns
                    OR l_live_kinds IS DISTINCT FROM l_stored_ordered_key_kinds
                THEN
                    RAISE EXCEPTION 'ordered-key index %.% no longer matches the stored key descriptor',
                        o_effective_schema_name, l_stored_ordered_key_index
                        USING ERRCODE = '55000';
                END IF;
            EXCEPTION
                WHEN undefined_object
                    OR feature_not_supported
                    OR wrong_object_type
                    OR invalid_parameter_value
                    OR undefined_table
                    OR invalid_schema_name THEN
                    -- The resolver's specific code is useful for a new run, but a
                    -- resume must fail with one stable code: the stored index is
                    -- no longer usable for these boundaries. A lost leaf privilege
                    -- (42501) is intentionally not normalized: the operator needs
                    -- the privilege error.
                    RAISE EXCEPTION 'ordered-key index %.% is no longer usable for run %: %',
                        o_effective_schema_name, l_stored_ordered_key_index, o_run_id, SQLERRM
                        USING ERRCODE = '55000';
            END;

            o_key_columns := l_stored_ordered_key_columns;
            o_key_kinds := l_stored_ordered_key_kinds;
        ELSIF i_boundary_key_index IS NOT NULL THEN
            RAISE NOTICE 'run for label % already exists; ignoring i_boundary_key_index for chunk_by %',
                i_label, l_stored_chunk_by;
        END IF;
        IF l_stored_alias IS DISTINCT FROM i_driving_table_alias THEN
            RAISE NOTICE 'run for label % already exists; using the stored driving table alias %',
                i_label, l_stored_alias;
        END IF;
        IF l_stored_sql_text IS DISTINCT FROM i_sql_text THEN
            RAISE NOTICE 'run for label % already exists; using the stored sql_text', i_label;
        END IF;
        IF l_stored_chunk_size IS DISTINCT FROM i_chunk_size THEN
            RAISE NOTICE 'run for label % already exists; using the stored chunk_size %',
                i_label, l_stored_chunk_size;
        END IF;
        IF l_stored_threads IS DISTINCT FROM i_threads::integer THEN
            RAISE NOTICE 'run for label % already exists; using the stored threads %',
                i_label, l_stored_threads;
        END IF;
        IF l_stored_sleep_ms IS DISTINCT FROM i_sleep_ms THEN
            RAISE NOTICE 'run for label % already exists; using the stored sleep_ms %',
                i_label, l_stored_sleep_ms;
        END IF;
        IF l_stored_schema_name IS DISTINCT FROM i_driving_table_schema_name
            OR l_stored_table_name IS DISTINCT FROM i_driving_table_name
        THEN
            RAISE NOTICE 'run for label % already exists; using the stored driving table %.%',
                i_label, l_stored_schema_name, l_stored_table_name;
        END IF;

        -- A block run records the physical filepath its boundaries were computed
        -- against; if the heap was rewritten since, the stored block boundaries
        -- are stale, so fail closed rather than resume against unrelated data. A
        -- primary-key run stored a NULL filepath, so this is a no-op for it.
        PERFORM dml_utils_lib.assert_relation_filepath(
                i_schema_name => o_effective_schema_name,
                i_table_name => o_effective_table_name,
                i_expected_filepath => l_stored_filepath);
    ELSE
        o_effective_sql_text := i_sql_text;
        o_effective_schema_name := i_driving_table_schema_name;
        o_effective_table_name := i_driving_table_name;
        o_effective_alias := i_driving_table_alias;
        o_chunk_by := i_chunk_by;
        o_partition_wise := i_partition_wise::boolean;

        -- Match the run-table constraint: an ordered_key run needs the index and
        -- the other strategies must not carry one. Reject locally so the error
        -- names the parameter instead of surfacing from the worker.
        PERFORM dml_utils_lib.assert_boundary_key_index(
                i_chunk_by => i_chunk_by,
                i_boundary_key_index => i_boundary_key_index);

        l_handle := public.pg_background_launch(pg_catalog.format(
                'SELECT dml_utils_lib.populate_migration_boundaries(%L, %L, %L, %L, %s, %s, %L, %L, %s, %L, %L) AS run_id',
                i_driving_table_schema_name,
                i_driving_table_name,
                i_label,
                i_sql_text,
                i_chunk_size,
                i_threads,
                i_chunk_by::text,
                i_driving_table_alias,
                i_sleep_ms,
                i_boundary_key_index,
                i_partition_wise::boolean));
        PERFORM public.pg_background_wait(l_handle.pid, l_handle.cookie);

        -- result() is one-time consumption and auto-detaches; it re-raises the
        -- worker's SQLSTATE (for example 23505 on a concurrent same-label run).
        SELECT run_id
        INTO l_worker_run_id
        FROM public.pg_background_result(l_handle.pid, l_handle.cookie) AS (run_id bigint);
        o_run_id := l_worker_run_id;

        -- Read the descriptor the worker stored, so the coordinator uses exactly
        -- the key the boundaries were built from. The run-row constraint normally
        -- guarantees it; fail closed if the row vanished or is malformed.
        IF o_chunk_by = 'ordered_key' THEN
            SELECT r.ordered_key_columns, r.ordered_key_kinds
            INTO o_key_columns, o_key_kinds
            FROM dml_utils_data.migration_run AS r
            WHERE r.run_id = o_run_id;

            IF NOT FOUND OR o_key_columns IS NULL OR o_key_kinds IS NULL THEN
                RAISE EXCEPTION 'run % has no stored ordered-key descriptor', o_run_id
                    USING ERRCODE = '55000';
            END IF;
        END IF;
    END IF;

    -- Resolve the key the coordinator uses to read boundary values. A new
    -- ordered_key run read its descriptor from the run above, and a resumed one
    -- validated and reused the stored descriptor, so only blocks and primary_key
    -- need resolving here. The placeholder column name is unused by the block
    -- renderer.
    IF o_chunk_by = 'blocks' THEN
        o_key_columns := ARRAY ['ctid']::name[];
        o_key_kinds := ARRAY ['bigint'];
    ELSIF o_chunk_by <> 'ordered_key' THEN
        o_key_columns := dml_utils_lib.primary_key_columns(
                i_schema_name => o_effective_schema_name,
                i_table_name => o_effective_table_name);
        o_key_kinds := dml_utils_lib.primary_key_kinds(
                i_schema_name => o_effective_schema_name,
                i_table_name => o_effective_table_name);
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.resolve_migration_run IS
    'Resolves or creates the active run for the label and returns the inputs the '
        'coordinator should use: the run id, whether it was already complete, the '
        'effective sql_text/schema/table/alias/threads/sleep_ms and chunking '
        'strategy (stored values for a resumed run), whether the run is '
        'partition-wise, and the boundary key columns and kinds (the stored '
        'ordered-key descriptor for ordered_key, the real primary key for '
        'primary_key, or a single bigint block number for blocks). '
        'A new run is created by populating its boundaries in a pg_background '
        'worker; a resumed run fails closed if the driving table''s physical '
        'filepath changed, and a resumed ordered-key run re-validates its stored '
        'index and descriptor, raising object_not_in_prerequisite_state (55000) if '
        'the index no longer matches.';
