CREATE OR REPLACE FUNCTION dml_utils_lib.primary_key_kinds(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS text[]
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_kinds text[];
    l_key       record;
BEGIN
    -- primary_key_attributes is the single catalog reader, including the
    -- schema/table and one-to-three-column validation; a NULL kind means the
    -- column type is unsupported.
    FOR l_key IN
        SELECT a.column_name, a.column_type, a.key_kind
        FROM dml_utils_lib.primary_key_attributes(
                     i_schema_name => i_schema_name,
                     i_table_name => i_table_name) AS a
        ORDER BY a.ordinality
        LOOP
            IF l_key.key_kind IS NULL THEN
                RAISE EXCEPTION 'primary key column %.%.% has unsupported type %',
                    i_schema_name, i_table_name, l_key.column_name, l_key.column_type
                    USING ERRCODE = '22023';
            END IF;
            l_key_kinds := l_key_kinds || l_key.key_kind;
        END LOOP;

    -- One kind per row read; the shared check compares that count with the key's
    -- declared arity so a catalog inconsistency is 55000, not a short array.
    PERFORM dml_utils_lib.assert_primary_key_arity_matches(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name,
            i_column_count => pg_catalog.cardinality(l_key_kinds));

    RETURN l_key_kinds;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.primary_key_kinds IS
    'Returns the boundary key kind (bigint, text, uuid or timestamptz) of each '
        'primary-key column in key order. primary_key_attributes validates the '
        'schema/table and the one-to-three-column limit; an unsupported column '
        'type is invalid_parameter_value (22023) and a catalog inconsistency '
        'between the kinds read and the declared arity is '
        'object_not_in_prerequisite_state (55000).';
