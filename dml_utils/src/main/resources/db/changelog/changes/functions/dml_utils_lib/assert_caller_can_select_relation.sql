CREATE OR REPLACE FUNCTION dml_utils_lib.assert_caller_can_select_relation(
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
    l_caller           name;
    l_rls              boolean;
    l_force            boolean;
    l_owner            oid;
    l_current_bypasses boolean;
    l_caller_bypasses  boolean;
BEGIN
    -- A SECURITY DEFINER routine must still authorize the driving-table scan with
    -- the effective caller. Recover it from SET ROLE (or the session user) and
    -- check the same privileges a direct SELECT would need before the table is
    -- read.
    l_caller := COALESCE(NULLIF(pg_catalog.current_setting('role', true), 'none'),
                         session_user)::name;

    -- A relation's SELECT privilege is not enough on its own: the caller also
    -- needs USAGE on the schema. Without it the caller cannot read the relation
    -- directly, yet the definer-backed scan would.
    IF NOT pg_catalog.has_schema_privilege(l_caller, i_schema_name, 'USAGE')
    THEN
        RAISE EXCEPTION 'caller lacks USAGE privilege on schema %', i_schema_name
            USING ERRCODE = '42501';
    END IF;

    IF NOT pg_catalog.has_table_privilege(
            l_caller,
            pg_catalog.format('%I.%I', i_schema_name, i_table_name),
            'SELECT')
    THEN
        RAISE EXCEPTION 'caller lacks SELECT privilege on table %.%',
            i_schema_name, i_table_name
            USING ERRCODE = '42501';
    END IF;

    -- has_table_privilege ignores row-level security. The definer-backed scan
    -- runs with the privileges of the definer (current_user), so when that role
    -- bypasses the policies (the table owner without FORCE, BYPASSRLS or a
    -- superuser) and the caller does not, the scan can read rows the caller could
    -- not read directly. Fail closed instead of widening the caller's view.
    SELECT c.relrowsecurity,
           c.relforcerowsecurity,
           c.relowner,
           r_current.rolsuper OR r_current.rolbypassrls,
           r_caller.rolsuper OR r_caller.rolbypassrls
    INTO l_rls, l_force, l_owner, l_current_bypasses, l_caller_bypasses
    FROM pg_catalog.pg_class AS c
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
             JOIN pg_catalog.pg_roles AS r_current ON r_current.rolname = current_user
             JOIN pg_catalog.pg_roles AS r_caller ON r_caller.rolname = l_caller
    WHERE n.nspname = i_schema_name
      AND c.relname = i_table_name;

    IF l_rls
        AND (l_current_bypasses
            OR (NOT l_force
                AND pg_catalog.pg_has_role(current_user, l_owner, 'USAGE')))
        AND NOT (l_caller_bypasses
            OR (NOT l_force
                AND pg_catalog.pg_has_role(l_caller, l_owner, 'USAGE')))
    THEN
        RAISE EXCEPTION 'relation %.% has row-level security; a definer-backed scan would bypass it for caller %',
            i_schema_name, i_table_name, l_caller
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_caller_can_select_relation IS
    'Raises insufficient_privilege (42501) when the effective caller lacks USAGE '
        'on the schema or SELECT on the named relation, and '
        'object_not_in_prerequisite_state (55000) when the relation has row-level '
        'security that the definer-backed scan would bypass for that caller. Used '
        'before a definer-backed scan.';
