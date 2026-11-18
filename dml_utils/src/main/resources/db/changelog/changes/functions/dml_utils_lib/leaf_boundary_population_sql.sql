CREATE OR REPLACE FUNCTION dml_utils_lib.leaf_boundary_population_sql(
    i_chunk_by dml_utils_data.chunking_strategy,
    i_chunk_size dml_utils_data.positive_integer,
    i_leaf_schema_name dml_utils_data.non_null_text,
    i_leaf_name dml_utils_data.non_null_text,
    i_run_id dml_utils_data.non_null_bigint,
    i_leaf_ordinal dml_utils_data.positive_integer
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_chunk_by   dml_utils_data.chunking_strategy;
    l_chunk_size integer;
    l_descriptor record;
BEGIN
    -- The run's stored strategy and chunk size are the single source of truth: the
    -- key descriptor below is resolved from the run too, so dispatching on the
    -- caller's values could render a statement for a different strategy or with a
    -- stride the run does not advertise.
    SELECT run.chunk_by,
           run.chunk_size
    INTO l_chunk_by, l_chunk_size
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- The strategy enum deliberately has no domain (a domain over it breaks
    -- jOOQ's generated class initialisation), so NULL is rejected explicitly.
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- IS DISTINCT FROM keeps the guard total: a NULL stored strategy (the column
    -- is NOT NULL today) must not fall through to the primary-key builder.
    IF i_chunk_by IS DISTINCT FROM l_chunk_by THEN
        RAISE EXCEPTION 'run % uses chunk_by %, not %', i_run_id, l_chunk_by, i_chunk_by
            USING ERRCODE = '22023';
    END IF;

    -- IS DISTINCT FROM even though the parameter domain rejects NULL: the
    -- comparison stays total if the types ever change.
    IF i_chunk_size IS DISTINCT FROM l_chunk_size THEN
        RAISE EXCEPTION 'run % uses chunk_size %, not %', i_run_id, l_chunk_size, i_chunk_size
            USING ERRCODE = '22023';
    END IF;

    IF l_chunk_by = 'blocks' THEN
        RETURN dml_utils_lib.build_block_leaf_boundary_population_sql(
                i_schema_name => i_leaf_schema_name,
                i_table_name => i_leaf_name,
                i_run_id => i_run_id,
                i_chunk_size => i_chunk_size::bigint,
                i_leaf_ordinal => i_leaf_ordinal);
    END IF;

    SELECT o_key_columns,
           o_key_kinds
    INTO l_descriptor
    FROM dml_utils_lib.migration_run_boundary_key(i_run_id => i_run_id);

    RETURN dml_utils_lib.build_leaf_boundary_population_sql(
            i_schema_name => i_leaf_schema_name,
            i_table_name => i_leaf_name,
            i_key_columns => l_descriptor.o_key_columns,
            i_key_kinds => l_descriptor.o_key_kinds,
            i_run_id => i_run_id,
            i_chunk_size => i_chunk_size::bigint,
            i_leaf_ordinal => i_leaf_ordinal);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.leaf_boundary_population_sql(
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) IS
    'Returns the statement that appends one claimed leaf''s chunk starts, '
        'dispatching on the run''s stored chunk_by (the same strategy that drives '
        'the key descriptor). The chunk size, run id and leaf ordinal are typed '
        'with the non-null/positive domains, so a NULL, zero or negative value and '
        'a NULL or blank schema/name fail before the statement is built. Raises '
        'invalid_parameter_value (22023) when i_chunk_by is NULL or differs from '
        'the stored strategy, when i_chunk_size differs from the stored size, and '
        'no_data_found (P0002) when the run does not exist. The caller executes the '
        'statement in the leaf calculation transaction.';
