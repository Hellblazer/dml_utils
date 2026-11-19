CREATE OR REPLACE FUNCTION dml_utils.explain_migration_run_chunk(
    i_run_id dml_utils_data.non_null_bigint,
    i_boundary_no dml_utils_data.non_negative_bigint
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
    l_schema_name           text;
    l_table_name            text;
    l_table_alias           text;
    l_sql_text              text;
    l_chunk_by              dml_utils_data.chunking_strategy;
    l_partition_wise        boolean;
    l_boundary              record;
    l_next_boundary         record;
    l_start_key             dml_utils_data.migration_key;
    l_end_key               dml_utils_data.migration_key;
    l_boundary_leaf_ordinal integer;
    l_next_leaf_ordinal     integer;
    l_last_boundary_no      bigint;
    l_is_final              boolean;
    l_leaf_schema_name      text;
    l_leaf_name             text;
    l_plan_schema_name      text;
    l_plan_table_name       text;
    l_key_columns           name[];
    l_key_kinds             text[];
    l_start_values          text[];
    l_end_values            text[];
    l_terminal_bigint       bigint[];
    l_terminal_text         text[];
    l_terminal_uuid         uuid[];
    l_terminal_timestamptz  timestamptz[];
    l_terminal_key          dml_utils_data.migration_key;
    l_prepared              record;
    l_sql                   text;
    l_plan                  json;
BEGIN

    SELECT run.sql_text,
           run.driving_table_schema_name::text,
           run.driving_table_name::text,
           run.driving_table_alias::text,
           run.chunk_by,
           run.partition_wise
    INTO l_sql_text, l_schema_name, l_table_name, l_table_alias,
        l_chunk_by, l_partition_wise
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    SELECT b.boundary_id, b.leaf_ordinal
    INTO l_boundary
    FROM dml_utils_data.migration_boundary AS b
    WHERE b.run_id = i_run_id
      AND b.boundary_no = i_boundary_no;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'boundary % for run % does not exist', i_boundary_no, i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    l_start_key := l_boundary.boundary_id;
    l_boundary_leaf_ordinal := l_boundary.leaf_ordinal;

    -- A chunk is a boundary plus the next boundary that closes it. The last
    -- boundary is the run's terminal high-water mark, not a chunk; a partially
    -- calculated run can also expose a last boundary with no successor yet.
    SELECT b.boundary_id, b.leaf_ordinal
    INTO l_next_boundary
    FROM dml_utils_data.migration_boundary AS b
    WHERE b.run_id = i_run_id
      AND b.boundary_no = i_boundary_no + 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'boundary % for run % has no following boundary and is not a chunk',
            i_boundary_no, i_run_id
            USING ERRCODE = '22023';
    END IF;

    l_end_key := l_next_boundary.boundary_id;
    l_next_leaf_ordinal := l_next_boundary.leaf_ordinal;

    -- The runner marks a chunk final when its end boundary is the run's last.
    -- These selection rules must stay in lockstep with
    -- dml_utils_lib/next_migration_chunk.sql (its o_is_final computation and the
    -- leaf-terminal high-water replacement below); the SQL-equality tests compare
    -- this function with that selector.
    SELECT pg_catalog.max(b.boundary_no)
    INTO l_last_boundary_no
    FROM dml_utils_data.migration_boundary AS b
    WHERE b.run_id = i_run_id;

    l_is_final := (i_boundary_no + 1 = l_last_boundary_no);

    IF l_partition_wise THEN
        -- Fail closed exactly like processing: the live leaf tree must still
        -- match the manifest before a stored range is explained.
        PERFORM dml_utils_lib.assert_migration_tree_unchanged(i_run_id => i_run_id);

        IF l_boundary_leaf_ordinal IS NULL THEN
            RAISE EXCEPTION 'boundary % for partition-wise run % has no manifest leaf',
                i_boundary_no, i_run_id
                USING ERRCODE = '55000';
        END IF;

        SELECT leaf.leaf_schema_name::text,
               leaf.leaf_name::text,
               (leaf.terminal_boundary_id).bigint_values,
               (leaf.terminal_boundary_id).text_values,
               (leaf.terminal_boundary_id).uuid_values,
               (leaf.terminal_boundary_id).timestamptz_values
        INTO l_leaf_schema_name, l_leaf_name, l_terminal_bigint, l_terminal_text,
            l_terminal_uuid, l_terminal_timestamptz
        FROM dml_utils_data.migration_run_leaf AS leaf
        WHERE leaf.run_id = i_run_id
          AND leaf.leaf_ordinal = l_boundary_leaf_ordinal;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'manifest leaf ordinal % is missing for partition-wise run %',
                l_boundary_leaf_ordinal, i_run_id
                USING ERRCODE = '55000';
        END IF;

        l_plan_schema_name := l_leaf_schema_name;
        l_plan_table_name := l_leaf_name;

        -- The next boundary belongs to a different leaf, so this is the last
        -- chunk of this leaf: end it at the manifest high-water snapshot with an
        -- inclusive bound, exactly as next_migration_chunk does.
        IF l_next_leaf_ordinal IS DISTINCT FROM l_boundary_leaf_ordinal THEN
            l_terminal_key := ROW (l_terminal_bigint, l_terminal_text,
                l_terminal_uuid,
                l_terminal_timestamptz)::dml_utils_data.migration_key;
            IF l_terminal_key IS NULL THEN
                RAISE EXCEPTION 'leaf ordinal % of run % has no terminal snapshot',
                    l_boundary_leaf_ordinal, i_run_id
                    USING ERRCODE = '55000';
            END IF;
            l_end_key := l_terminal_key;
            l_is_final := true;
        END IF;
    ELSE
        l_plan_schema_name := l_schema_name;
        l_plan_table_name := l_table_name;
    END IF;

    -- The boundary key the run was created with, so a resumed or explain-only
    -- call uses exactly the descriptor the runner used.
    IF l_chunk_by = 'blocks' THEN
        l_key_columns := ARRAY ['ctid']::name[];
        l_key_kinds := ARRAY ['bigint'];
    ELSE
        SELECT key.o_key_columns, key.o_key_kinds
        INTO l_key_columns, l_key_kinds
        FROM dml_utils_lib.migration_run_boundary_key(i_run_id => i_run_id) AS key;

        IF l_key_columns IS NULL OR l_key_kinds IS NULL THEN
            RAISE EXCEPTION 'run % has no stored boundary key', i_run_id
                USING ERRCODE = '55000';
        END IF;
    END IF;

    SELECT pair.o_start_values, pair.o_end_values
    INTO l_start_values, l_end_values
    FROM dml_utils_lib.migration_key_pair_values(
                 i_start_key => l_start_key,
                 i_end_key => l_end_key,
                 i_key_kinds => l_key_kinds) AS pair;

    IF l_chunk_by = 'blocks' THEN
        -- Block chunks are half-open ctid ranges and have no final/inclusive
        -- distinction; the plan kind therefore mirrors the synthetic block plans.
        o_plan_kind := 'chunk_non_final';
        l_sql := dml_utils_lib.render_block_chunk_sql(
                i_sql_text => l_sql_text,
                i_schema_name => l_plan_schema_name::dml_utils_data.non_null_text,
                i_table_name => l_plan_table_name::dml_utils_data.non_null_text,
                i_table_alias => l_table_alias,
                i_start_block => l_start_values[1]::bigint,
                i_end_block => l_end_values[1]::bigint);
        l_plan := dml_utils_lib.explain_query_plan(i_sql_text => l_sql);
    ELSE
        -- Render through the same two steps as the coordinator: prepare the
        -- run-invariant context against the driving parent, then render against
        -- the chunk's table (the parent or one leaf).
        l_prepared := dml_utils_lib.prepare_chunk_sql_render(
                i_sql_text => l_sql_text,
                i_schema_name => l_schema_name::dml_utils_data.non_null_text,
                i_table_name => l_table_name::dml_utils_data.non_null_text,
                i_table_alias => l_table_alias,
                i_key_columns => l_key_columns,
                i_key_kinds => l_key_kinds);
        o_plan_kind := CASE WHEN l_is_final THEN 'chunk_final' ELSE 'chunk_non_final' END;
        l_sql := dml_utils_lib.render_prepared_chunk_sql(
                i_template => l_prepared.o_template,
                i_schema_name => l_plan_schema_name::dml_utils_data.non_null_text,
                i_table_name => l_plan_table_name::dml_utils_data.non_null_text,
                i_table_alias => l_table_alias,
                i_column_names => l_prepared.o_column_names,
                i_key_kinds => l_key_kinds,
                i_key_collations => l_prepared.o_key_collations,
                i_start_values => l_start_values,
                i_end_values => l_end_values,
                i_is_final => l_is_final);
        l_plan := dml_utils_lib.explain_query_plan(i_sql_text => l_sql);
    END IF;

    RETURN QUERY
        SELECT o_plan_kind,
               l_sql,
               l_plan,
               l_leaf_schema_name,
               l_leaf_name,
               l_boundary_leaf_ordinal;
END;
$$;

COMMENT ON FUNCTION dml_utils.explain_migration_run_chunk IS
    'Returns the EXPLAIN (FORMAT JSON) plan for one real stored chunk of a run, '
        'selected by run id and boundary number, without decoding the boundary key '
        'or selecting a leaf. It resolves the boundary and its successor exactly as '
        'the coordinator does, ends the last chunk of a partition-wise leaf at the '
        'manifest high-water snapshot with an inclusive bound, renders the same SQL '
        'the runner would execute (primary_key and ordered_key use the stored key '
        'descriptor; blocks render a half-open ctid range), and returns the plan '
        'kind, SQL, plan and resolved leaf. A terminal or successor-less boundary '
        'is rejected with 22023; a missing run or boundary raises P0002. A changed '
        'partition tree raises 55000 before the plan is produced. It resolves no '
        'run, writes nothing and reads no user rows.';
