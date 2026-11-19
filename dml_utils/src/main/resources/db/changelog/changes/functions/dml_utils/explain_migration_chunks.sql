CREATE OR REPLACE FUNCTION dml_utils.explain_migration_chunks(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000,
    i_driving_table_alias dml_utils_data.non_null_text DEFAULT 't',
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- The unique index whose order an ordered_key run uses. Required for
    -- ordered_key and rejected for the other strategies.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, resolve a leaf of the declarative partitioned parent and render
    -- the synthetic plans against that leaf.
    i_partition_wise dml_utils_data.non_null_boolean DEFAULT FALSE,
    -- Deterministic manifest ordinal; NULL selects the first leaf. Rejected for a
    -- non-partition-wise call.
    i_leaf_ordinal integer DEFAULT NULL
)
    RETURNS TABLE
            (
                o_plan_kind        text,
                o_sql_text         text,
                o_plan             json,
                o_leaf_schema_name text,
                o_leaf_name        text,
                o_leaf_ordinal     integer
            )
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_columns      name[];
    l_key_kinds        text[];
    l_leaf_schema_name text;
    l_leaf_name        text;
    l_leaf_ordinal     integer;
    l_plan_schema_name text;
    l_plan_table_name  text;
BEGIN
    -- Validate the driving table and its key the same way the runner does,
    -- before any plan is produced. The template itself is validated by
    -- dml_utils_lib.explain_chunk_plans.
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_driving_table_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name);

    PERFORM dml_utils_lib.assert_boundary_key_index(
            i_chunk_by => i_chunk_by,
            i_boundary_key_index => i_boundary_key_index);

    l_plan_schema_name := i_driving_table_schema_name::text;
    l_plan_table_name := i_driving_table_name::text;

    IF i_partition_wise::boolean THEN
        -- leaf_partitions validates the parent, every leaf relation and the
        -- caller's SELECT on each leaf, and returns the deterministic manifest
        -- order. Resolve the requested ordinal (NULL means the first leaf).
        SELECT leaf.leaf_ordinal, leaf.leaf_schema_name, leaf.leaf_name
        INTO l_leaf_ordinal, l_leaf_schema_name, l_leaf_name
        FROM dml_utils_lib.leaf_partitions(
                     i_schema_name => i_driving_table_schema_name,
                     i_table_name => i_driving_table_name,
                     i_chunk_by => i_chunk_by) AS leaf
        WHERE i_leaf_ordinal IS NULL
           OR leaf.leaf_ordinal = i_leaf_ordinal
        ORDER BY leaf.leaf_ordinal
        LIMIT 1;

        IF NOT FOUND THEN
            IF i_leaf_ordinal IS NULL THEN
                RAISE EXCEPTION 'partitioned parent %.% has no storage leaves',
                    i_driving_table_schema_name, i_driving_table_name
                    USING ERRCODE = '22023';
            END IF;
            RAISE EXCEPTION 'partition leaf ordinal % does not exist for %.%',
                i_leaf_ordinal, i_driving_table_schema_name, i_driving_table_name
                USING ERRCODE = '22023';
        END IF;

        l_plan_schema_name := l_leaf_schema_name;
        l_plan_table_name := l_leaf_name;
    ELSIF i_leaf_ordinal IS NOT NULL THEN
        RAISE EXCEPTION 'i_leaf_ordinal requires i_partition_wise'
            USING ERRCODE = '22023';
    END IF;

    IF i_chunk_by = 'blocks' THEN
        PERFORM dml_utils_lib.assert_plain_heap(
                i_schema_name => l_plan_schema_name::dml_utils_data.non_null_text,
                i_table_name => l_plan_table_name::dml_utils_data.non_null_text);
        -- The placeholder columns and kind are unused by the block renderer.
        l_key_columns := ARRAY ['ctid']::name[];
        l_key_kinds := ARRAY ['bigint'];
    ELSIF i_chunk_by = 'ordered_key' THEN
        IF i_partition_wise::boolean THEN
            SELECT pg_catalog.array_agg(a.column_name ORDER BY a.ordinality),
                   pg_catalog.array_agg(a.key_kind ORDER BY a.ordinality)
            INTO l_key_columns, l_key_kinds
            FROM dml_utils_lib.partitioned_ordered_key_attributes(
                         i_schema_name => i_driving_table_schema_name,
                         i_table_name => i_driving_table_name,
                         i_index_name => i_boundary_key_index) AS a;

            IF l_key_columns IS NULL OR l_key_kinds IS NULL THEN
                RAISE EXCEPTION 'partition-wise ordered key %.% resolved no key columns',
                    i_driving_table_schema_name, i_driving_table_name
                    USING ERRCODE = '55000';
            END IF;
        ELSE
            -- One traversal returns both arrays: ordered_key_attributes validates
            -- the index (the same checks assert_usable_ordered_key, ordered_key_
            -- columns and ordered_key_kinds would repeat) and yields one row per
            -- key column, so the aggregates are ordered and aligned.
            SELECT pg_catalog.array_agg(a.column_name ORDER BY a.ordinality),
                   pg_catalog.array_agg(a.key_kind ORDER BY a.ordinality)
            INTO l_key_columns, l_key_kinds
            FROM dml_utils_lib.ordered_key_attributes(
                         i_schema_name => i_driving_table_schema_name,
                         i_table_name => i_driving_table_name,
                         i_index_name => i_boundary_key_index) AS a;

            IF l_key_columns IS NULL OR l_key_kinds IS NULL THEN
                RAISE EXCEPTION 'ordered key %.% resolved no key columns',
                    i_driving_table_schema_name, i_driving_table_name
                    USING ERRCODE = '55000';
            END IF;
        END IF;
    ELSE
        l_key_columns := dml_utils_lib.primary_key_columns(
                i_schema_name => i_driving_table_schema_name,
                i_table_name => i_driving_table_name);
        l_key_kinds := dml_utils_lib.primary_key_kinds(
                i_schema_name => i_driving_table_schema_name,
                i_table_name => i_driving_table_name);
    END IF;

    RETURN QUERY
        SELECT p.o_plan_kind,
               p.o_sql_text,
               p.o_plan,
               l_leaf_schema_name,
               l_leaf_name,
               l_leaf_ordinal
        FROM dml_utils_lib.explain_chunk_plans(
                     i_sql_text => i_sql_text,
                     i_schema_name => l_plan_schema_name::dml_utils_data.non_null_text,
                     i_table_name => l_plan_table_name::dml_utils_data.non_null_text,
                     i_table_alias => i_driving_table_alias,
                     i_key_columns => l_key_columns,
                     i_key_kinds => l_key_kinds,
                     i_chunk_size => i_chunk_size::bigint,
                     i_chunk_by => i_chunk_by) AS p;
END;
$$;

COMMENT ON FUNCTION dml_utils.explain_migration_chunks IS
    'Returns the EXPLAIN (FORMAT JSON) plans for the SQL a chunked run of the '
        'template would generate: the boundary-population insert, a non-final '
        'chunk and the final chunk, as rows boundary_population, chunk_non_final '
        'and chunk_final, for the given chunking strategy (primary_key, ordered_key '
        'or blocks; for blocks both chunk plans are half-open ctid ranges). An '
        'ordered_key run requires i_boundary_key_index. With i_partition_wise the '
        'plans are rendered against one leaf of the declarative partitioned parent, '
        'selected by i_leaf_ordinal (NULL means the first leaf); the returned leaf '
        'schema/name/ordinal identify it. A leaf selector on a non-partition-wise '
        'call is rejected. It resolves no run, reads no boundaries and writes '
        'nothing; the chunk ranges are synthetic, so their estimates may differ '
        'from a real chunk. Use it to inspect the plans before running '
        'dml_utils.run_migration_chunks.';
