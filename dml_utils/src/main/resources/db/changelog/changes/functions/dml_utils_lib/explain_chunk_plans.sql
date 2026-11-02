CREATE OR REPLACE FUNCTION dml_utils_lib.explain_chunk_plans(
    i_sql_text dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_primary_key_columns name[],
    i_key_kinds text[],
    i_chunk_size bigint,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key'
)
    RETURNS TABLE
            (
                o_plan_kind text,
                o_sql_text  text,
                o_plan      json
            )
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_range record;
BEGIN
    -- Validate the template once here, so both caller-facing explain functions
    -- (a raw template and the function wrapper) share the check.
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    IF i_chunk_by = 'blocks' THEN
        -- 1. The block boundary-population insert, with a synthetic run id (0):
        -- EXPLAIN plans the insert as a ModifyTable and does not evaluate the
        -- foreign key, so no run needs to exist and nothing is written.
        o_plan_kind := 'boundary_population';
        o_sql_text := dml_utils_lib.build_block_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_run_id => 0,
                i_chunk_size => i_chunk_size);
        o_plan := dml_utils_lib.explain_query_plan(i_sql_text => o_sql_text);
        RETURN NEXT;

        -- 2 and 3. Two representative half-open block ranges. There is no
        -- final/inclusive distinction for blocks, so chunk_final is simply a
        -- later chunk; both are half-open ctid ranges.
        o_plan_kind := 'chunk_non_final';
        o_sql_text := dml_utils_lib.render_block_chunk_sql(
                i_sql_text => i_sql_text,
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_table_alias => i_table_alias,
                i_start_block => 0,
                i_end_block => i_chunk_size);
        o_plan := dml_utils_lib.explain_query_plan(i_sql_text => o_sql_text);
        RETURN NEXT;

        o_plan_kind := 'chunk_final';
        o_sql_text := dml_utils_lib.render_block_chunk_sql(
                i_sql_text => i_sql_text,
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_table_alias => i_table_alias,
                i_start_block => i_chunk_size,
                i_end_block => 2 * i_chunk_size);
        o_plan := dml_utils_lib.explain_query_plan(i_sql_text => o_sql_text);
        RETURN NEXT;

        RETURN;
    END IF;

    -- 1. The statement that scans the driving table in primary-key order and
    -- inserts the run's boundaries. The run id is synthetic (0): EXPLAIN plans
    -- the insert as a ModifyTable and does not evaluate the foreign key, so no
    -- run needs to exist and nothing is written.
    o_plan_kind := 'boundary_population';
    o_sql_text := dml_utils_lib.build_boundary_population_sql(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name,
            i_key_columns => i_primary_key_columns,
            i_key_kinds => i_key_kinds,
            i_run_id => 0,
            i_chunk_size => i_chunk_size);
    o_plan := dml_utils_lib.explain_query_plan(i_sql_text => o_sql_text);
    RETURN NEXT;

    -- 2 and 3. A representative non-final and final chunk, rendered from
    -- synthetic ranges so no boundaries are read. ORDER BY o_is_final puts the
    -- non-final (false) row before the final (true) one.
    FOR l_range IN
        SELECT v.o_is_final, v.o_start_values, v.o_end_values
        FROM dml_utils_lib.synthetic_chunk_boundary_values(
                     i_key_kinds => i_key_kinds,
                     i_chunk_size => i_chunk_size) AS v
        ORDER BY v.o_is_final
        LOOP
            o_plan_kind := CASE WHEN l_range.o_is_final THEN 'chunk_final' ELSE 'chunk_non_final' END;
            o_sql_text := dml_utils_lib.render_chunk_sql(
                    i_sql_text => i_sql_text,
                    i_schema_name => i_schema_name,
                    i_table_name => i_table_name,
                    i_table_alias => i_table_alias,
                    i_primary_key_columns => i_primary_key_columns,
                    i_key_kinds => i_key_kinds,
                    i_start_values => l_range.o_start_values,
                    i_end_values => l_range.o_end_values,
                    i_is_final => l_range.o_is_final);
            o_plan := dml_utils_lib.explain_query_plan(i_sql_text => o_sql_text);
            RETURN NEXT;
        END LOOP;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.explain_chunk_plans IS
    'Returns the EXPLAIN (FORMAT JSON) plans for the three statements a chunked '
        'run generates: the boundary-population insert (with a synthetic run id), '
        'a non-final chunk and the final chunk, for the given chunking strategy '
        '(primary_key or blocks). It reads no boundaries and writes nothing; the '
        'chunk ranges are synthetic. Shared by dml_utils.explain_migration_chunks '
        'and dml_utils.explain_function_over_table.';
