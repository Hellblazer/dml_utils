CREATE OR REPLACE FUNCTION dml_utils.run_function_over_table(
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000,
    i_threads dml_utils_data.positive_integer DEFAULT 1,
    -- Plain text, not non_null_text: an omitted label is NULL and the body
    -- derives one. non_null_text would reject the DEFAULT NULL before the body.
    i_label text DEFAULT NULL,
    -- Plain text for the same reason: an omitted filter is NULL and means no
    -- filter. When set, it is ANDed onto every chunk's range predicate.
    i_filter text DEFAULT NULL,
    -- The chunking strategy. blocks requires a quiescent, read-only driving
    -- table on a plain heap (see the block-strategy limitations in the README);
    -- it is unsafe when the function updates the driving table in place.
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- How long the chunk coordinator waits for a worker before re-checking; see
    -- dml_utils.run_migration_chunks. 1ms keeps latency low; raise it for chunks
    -- that run in non-trivial time to cut coordinator CPU.
    i_poll_timeout_ms dml_utils_data.positive_integer DEFAULT 1,
    -- Initial delay after each completed chunk for a new run; a resumed run uses
    -- its stored sleep_ms.
    i_sleep_ms dml_utils_data.non_negative_integer DEFAULT 0,
    -- The unique index whose order an ordered_key run uses. Required for
    -- ordered_key and rejected for the other strategies.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, the driving table is a declarative partitioned parent and the
    -- chosen strategy is applied inside each leaf.
    i_partition_wise dml_utils_data.non_null_boolean DEFAULT FALSE,
    -- Initial transient-retry policy for a new run; a resumed run uses its stored
    -- values.
    i_max_retries dml_utils_data.non_negative_integer DEFAULT 3,
    i_retry_backoff_ms dml_utils_data.non_negative_integer DEFAULT 1000
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_function_chunk_template text;
    l_filter_hash             text;
    l_label_parts             json[];
    l_label                   text;
BEGIN
    -- Build and validate the template first: it resolves the driving table's
    -- primary key and checks the supplied function exists, returns void, and
    -- takes the primary-key column types in key order. A bad table or function
    -- fails before any run or worker exists. The chunk key is independent of the
    -- function arguments: ordered_key and partition_wise are forwarded to the
    -- base engine.
    l_function_chunk_template := dml_utils_lib.build_function_chunk_template(
            i_table_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name,
            i_function_schema_name => i_function_schema_name,
            i_function_name => i_function_name,
            i_filter => i_filter);

    -- Derive a deterministic label from the driving table, function, filter,
    -- chunking strategy, ordered-key index and partition flag when the caller does
    -- not supply one, so re-running the same call resumes the same run instead of
    -- creating a new one. The components are joined as a JSON array, which is
    -- unambiguous even when a (quoted) name contains a '.', ':' or other
    -- delimiter: JSON escapes and quotes each element, so distinct inputs cannot
    -- collide. The filter and strategy are part of the label, so calls that differ
    -- only in those derive different runs instead of the second silently resuming
    -- the first with its stored values.
    --
    -- The strategy is rendered from its text form (the bare enum label) rather
    -- than the enum value, so the label cannot pick up a session-dependent type
    -- rendering. search_path is deliberately not pinned here: this function
    -- forwards the caller's session into run_migration_chunks, which calls
    -- pg_background, and pg_background's own routines reference its
    -- pg_background_handle type unqualified.
    --
    -- The filter is hashed because label is indexed by three btree indexes; a
    -- large IN list or regex would otherwise exceed the btree tuple limit and
    -- fail run creation with an opaque "index row size" error.
    IF i_label IS NULL THEN
        l_filter_hash := CASE
                             WHEN i_filter IS NULL THEN NULL
                             ELSE 'sha256:' || pg_catalog.encode(
                                     pg_catalog.sha256(pg_catalog.convert_to(i_filter, 'UTF8')),
                                     'hex')
            END;

        l_label_parts := ARRAY [
            pg_catalog.to_json(i_driving_table_schema_name),
            pg_catalog.to_json(i_driving_table_name),
            pg_catalog.to_json(i_function_schema_name),
            pg_catalog.to_json(i_function_name),
            pg_catalog.to_json(l_filter_hash),
            pg_catalog.to_json(i_chunk_by::text)
            ];

        -- Keep the v1 six-component shape when the ordered-key index is absent and
        -- partition_wise is false, so a plain call still resumes a run created
        -- before this change. The key and partition flag are appended only when
        -- they can distinguish a run.
        IF i_boundary_key_index IS NOT NULL OR i_partition_wise::boolean THEN
            l_label_parts := l_label_parts
                || ARRAY [
                                 pg_catalog.to_json(i_boundary_key_index::text),
                                 pg_catalog.to_json(i_partition_wise::boolean)
                                 ];
        END IF;

        l_label := 'function:' || pg_catalog.json_build_array(VARIADIC l_label_parts)::text;
    ELSE
        -- An explicit label skips the derivation (and the filter hash, which can
        -- be expensive for a large IN list or regex).
        l_label := i_label;
    END IF;

    -- Delegate to the base engine: it owns resume, chunk scheduling, error
    -- recording and run completion. The generated template is a valid
    -- <driving_table>/<chunking_clause> template, so no special path is needed.
    PERFORM dml_utils.run_migration_chunks(
            i_sql_text => l_function_chunk_template,
            i_driving_table_schema_name => i_driving_table_schema_name,
            i_driving_table_name => i_driving_table_name,
            i_label => l_label,
            i_chunk_size => i_chunk_size,
            i_threads => i_threads,
            i_chunk_by => i_chunk_by,
            i_poll_timeout_ms => i_poll_timeout_ms,
            i_sleep_ms => i_sleep_ms,
            i_boundary_key_index => i_boundary_key_index,
            i_partition_wise => i_partition_wise,
            i_max_retries => i_max_retries,
            i_retry_backoff_ms => i_retry_backoff_ms);
END;
$$;

COMMENT ON FUNCTION dml_utils.run_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    text,
    text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) IS
    'Runs a user-supplied void function once per row of the driving table, chunk '
        'by chunk, resuming by label (derived from the table, function, filter, '
        'chunk key and partition flag when i_label is NULL; the filter is hashed so '
        'the label stays within the btree index limit). The function must take the '
        'primary-key column types in key order and return void; the chunk key is '
        'independent. A non-NULL i_filter is ANDed onto every chunk''s range '
        'predicate, so only the rows it matches are passed to the function. '
        'i_chunk_by selects the chunking strategy; ordered_key uses '
        'i_boundary_key_index, and i_partition_wise applies the strategy inside each '
        'leaf of a declarative partitioned parent. blocks requires a quiescent, '
        'read-only driving table and is unsafe when the function updates the '
        'driving table in place. i_sleep_ms seeds a new run''s per-chunk delay; a '
        'resumed run uses its stored value. i_max_retries and i_retry_backoff_ms '
        'seed a new run''s transient-retry policy; a resumed run uses the stored '
        'values.';
