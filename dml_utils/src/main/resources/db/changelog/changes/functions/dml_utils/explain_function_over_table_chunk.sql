CREATE OR REPLACE FUNCTION dml_utils.explain_function_over_table_chunk(
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text,
    i_start_values text[],
    i_end_values text[],
    i_is_final dml_utils_data.non_null_boolean,
    -- Plain text, not non_null_text: an omitted filter is NULL and means no
    -- filter. non_null_text would reject the DEFAULT NULL before the body.
    i_filter text DEFAULT NULL,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- The unique index whose order an ordered_key chunk uses. Required for
    -- ordered_key and rejected for the other strategies.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, the chunk is rendered against one leaf of a declarative
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
    -- template whose alias is fixed at 't', so the public explicit-chunk path
    -- resolves the requested leaf and plans it unchanged. It rejects blocks, as
    -- the explicit form cannot express a ctid range.
    RETURN QUERY
        SELECT p.o_plan_kind,
               p.o_sql_text,
               p.o_plan,
               p.o_leaf_schema_name,
               p.o_leaf_name,
               p.o_leaf_ordinal
        FROM dml_utils.explain_migration_chunk(
                     i_sql_text => l_function_chunk_template,
                     i_driving_table_schema_name => i_driving_table_schema_name,
                     i_driving_table_name => i_driving_table_name,
                     i_start_values => i_start_values,
                     i_end_values => i_end_values,
                     i_is_final => i_is_final,
                     i_driving_table_alias => 't',
                     i_chunk_by => i_chunk_by,
                     i_boundary_key_index => i_boundary_key_index,
                     i_partition_wise => i_partition_wise,
                     i_leaf_ordinal => i_leaf_ordinal) AS p;
END;
$$;

COMMENT ON FUNCTION dml_utils.explain_function_over_table_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) IS
    'Returns the EXPLAIN (FORMAT JSON) plan for one explicitly supplied chunk of '
        'a run_function_over_table call, as a single chunk_non_final or '
        'chunk_final row. i_chunk_by selects the key: primary_key (the default) or '
        'ordered_key with i_boundary_key_index. Blocks are rejected because a '
        'block chunk is a half-open ctid range and cannot be expressed here (see '
        'dml_utils.explain_function_over_table for representative block-chunk '
        'plans). The function must return void with argument types matching the '
        'driving table''s primary key, in key order; the chunk key is independent. '
        'The start and end values are one text value per key column, in key order; '
        'a non-NULL i_filter is ANDed onto the chunk predicate. With '
        'i_partition_wise the chunk is rendered against one leaf of a declarative '
        'partitioned parent, selected by i_leaf_ordinal (NULL means the first '
        'leaf), and the returned leaf schema/name/ordinal identify it. It resolves '
        'no run, reads no boundaries and writes nothing.';
