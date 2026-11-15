CREATE OR REPLACE FUNCTION dml_utils_lib.assert_table_exists(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_relkind text;
BEGIN
    -- Resolve existence and kind separately: reporting a view or foreign table as
    -- "does not exist" hides the real problem. Unknown the same way as
    -- assert_plain_heap / assert_partitioned_table: 42P01 when missing, 42809
    -- when the relation exists but is not a plain or partitioned table.
    SELECT c.relkind::text
    INTO l_relkind
    FROM pg_catalog.pg_class AS c
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE n.nspname = i_schema_name::name
      AND c.relname = i_table_name::name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'table %.% does not exist', i_schema_name, i_table_name
            USING ERRCODE = '42P01';
    END IF;
    IF l_relkind NOT IN ('r', 'p') THEN
        RAISE EXCEPTION 'relation %.% is not a table (relkind %)',
            i_schema_name, i_table_name, l_relkind
            USING ERRCODE = '42809';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_table_exists IS
    'Raises undefined_table (42P01) when the relation does not exist and '
        'wrong_object_type (42809) when it exists but is not a plain or '
        'partitioned table.';
