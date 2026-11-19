CREATE OR REPLACE FUNCTION dml_utils_lib.primary_key_columns(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS name[]
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_columns name[];
BEGIN
    -- primary_key_attributes is the single catalog reader: it validates the
    -- schema and table and the one-to-three-column limit, and returns the key
    -- columns in key order.
    SELECT pg_catalog.array_agg(a.column_name ORDER BY a.ordinality)
    INTO l_primary_key_columns
    FROM dml_utils_lib.primary_key_attributes(
                 i_schema_name => i_schema_name,
                 i_table_name => i_table_name) AS a;

    -- The array is built from that one read, so its length is the count the
    -- shared check compares with the key's declared arity: callers can zip it with
    -- primary_key_kinds without a misaligned pair slipping through.
    PERFORM dml_utils_lib.assert_primary_key_arity_matches(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name,
            i_column_count => pg_catalog.cardinality(l_primary_key_columns));

    RETURN l_primary_key_columns;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.primary_key_columns IS
    'Returns the primary-key columns in key order. primary_key_attributes '
        'validates the schema/table and the one-to-three-column limit '
        '(invalid_parameter_value 22023), and a catalog inconsistency between the '
        'columns read and the declared arity is object_not_in_prerequisite_state '
        '(55000).';
