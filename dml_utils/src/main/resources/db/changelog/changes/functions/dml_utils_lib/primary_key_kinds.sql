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
    l_key_column_count smallint;
    l_key_kinds        text[];
    l_key              record;
BEGIN
    -- Read the expected arity first (as primary_key_columns does). This is STABLE,
    -- so both reads share the caller's snapshot: the guard is a self-check that
    -- the callee returned one kind per key column, not a concurrency detector.
    SELECT i.indnkeyatts
    INTO l_key_column_count
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE i.indisprimary
      AND n.nspname = i_schema_name::name
      AND c.relname = i_table_name::name;

    -- primary_key_attributes returns each column's collapsed kind in key order;
    -- a NULL kind means an unsupported type.
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

    IF pg_catalog.cardinality(l_key_kinds) IS DISTINCT FROM l_key_column_count::integer
    THEN
        RAISE EXCEPTION 'primary key of table %.% changed while reading its kinds',
            i_schema_name, i_table_name
            USING ERRCODE = '55000';
    END IF;

    RETURN l_key_kinds;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.primary_key_kinds IS
    'Returns the boundary key kind (bigint, text, uuid or timestamptz) of each '
        'primary-key column in key order, raising invalid_parameter_value (22023) '
        'for an unsupported type or a primary key that changed while reading.';
