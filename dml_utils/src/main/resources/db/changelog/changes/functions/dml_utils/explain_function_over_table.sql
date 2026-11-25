CREATE OR REPLACE FUNCTION dml_utils.explain_function_over_table(
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000,
    -- Plain text, not non_null_text: an omitted filter is NULL and means no
    -- filter. non_null_text would reject the DEFAULT NULL before the body.
    i_filter text DEFAULT NULL,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- The unique index whose order an ordered_key run uses. Required for
    -- ordered_key and rejected for the other strategies.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, the plans are rendered against one leaf of a declarative
    -- partitioned parent.
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
    l_function_chunk_template text;
BEGIN
    -- Validate the function against the driving table's primary key and build
    -- the per-row template first, exactly as the runner does; a bad table or
    -- function fails before any plan is produced. The chunk key is independent of
    -- the function arguments.
    l_function_chunk_template := dml_utils_lib.build_function_chunk_template(
            i_table_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name,
            i_function_schema_name => i_function_schema_name,
            i_function_name => i_function_name,
            i_filter => i_filter);

    -- The generated template is a valid <driving_table>/<chunking_clause>
    -- template whose alias is fixed at 't', so the public explain path renders,
    -- resolves the requested leaf and plans it unchanged.
    RETURN QUERY
        SELECT p.o_plan_kind,
               p.o_sql_text,
               p.o_plan,
               p.o_leaf_schema_name,
               p.o_leaf_name,
               p.o_leaf_ordinal
        FROM dml_utils.explain_migration_chunks(
                     i_sql_text => l_function_chunk_template,
                     i_driving_table_schema_name => i_driving_table_schema_name,
                     i_driving_table_name => i_driving_table_name,
                     i_chunk_size => i_chunk_size,
                     i_driving_table_alias => 't',
                     i_chunk_by => i_chunk_by,
                     i_boundary_key_index => i_boundary_key_index,
                     i_partition_wise => i_partition_wise,
                     i_leaf_ordinal => i_leaf_ordinal) AS p;
END;
$$;

COMMENT ON FUNCTION dml_utils.explain_function_over_table IS
    'Returns the EXPLAIN (FORMAT JSON) plans for the SQL a '
        'run_function_over_table call would generate: the boundary-population '
        'insert, a non-final chunk and the final chunk, as rows '
        'boundary_population, chunk_non_final and chunk_final, for the given '
        'chunking strategy (primary_key, ordered_key or blocks). The function must '
        'return void with argument types matching the driving table''s primary key, '
        'in key order; the chunk key is independent. A non-NULL i_filter is ANDed '
        'onto the chunk plans'' range predicate (the boundary-population plan is '
        'unaffected). With i_partition_wise the plans are rendered against one leaf '
        'of a declarative partitioned parent, selected by i_leaf_ordinal (NULL '
        'means the first leaf), and the returned leaf schema/name/ordinal identify '
        'it. It resolves no run, reads no boundaries and writes nothing; the chunk '
        'ranges are synthetic, so their estimates may differ from a real chunk.';
