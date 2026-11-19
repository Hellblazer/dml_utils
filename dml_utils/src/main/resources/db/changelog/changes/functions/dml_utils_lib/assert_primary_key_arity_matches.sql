CREATE OR REPLACE FUNCTION dml_utils_lib.assert_primary_key_arity_matches(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_column_count dml_utils_data.non_negative_integer
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_column_count smallint;
BEGIN
    -- Read the declared arity. This is the only place that compares it with the
    -- rows a caller read from primary_key_attributes, so primary_key_columns and
    -- primary_key_kinds (and resolve_function_chunk_target) share one contract.
    SELECT i.indnkeyatts
    INTO l_key_column_count
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE i.indisprimary
      AND n.nspname = i_schema_name::name
      AND c.relname = i_table_name::name;

    IF l_key_column_count IS NULL THEN
        RAISE EXCEPTION 'table %.% has no primary key', i_schema_name, i_table_name
            USING ERRCODE = '22023';
    END IF;

    -- primary_key_attributes returns one row per key column, so the caller's
    -- count must equal the declared arity. Both reads share the caller's snapshot
    -- (STABLE), so a mismatch is a catalog inconsistency, not a concurrent
    -- change: fail loudly rather than let callers zip misaligned arrays.
    IF i_column_count::integer IS DISTINCT FROM l_key_column_count::integer THEN
        RAISE EXCEPTION 'primary key of table %.% is inconsistent in the catalog (% columns read, % declared)',
            i_schema_name, i_table_name, i_column_count, l_key_column_count
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_primary_key_arity_matches(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer
    ) IS
    'Asserts that the number of primary-key columns a caller read from '
        'primary_key_attributes equals the key''s declared arity (indnkeyatts). '
        'Both reads share the caller''s snapshot, so a mismatch is a catalog '
        'inconsistency, reported as object_not_in_prerequisite_state (55000) '
        'instead of letting callers zip misaligned column and kind arrays; a '
        'table without a primary key is invalid_parameter_value (22023).';
