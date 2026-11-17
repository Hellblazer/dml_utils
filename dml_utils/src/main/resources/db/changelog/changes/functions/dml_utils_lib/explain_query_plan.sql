CREATE OR REPLACE FUNCTION dml_utils_lib.explain_query_plan(
    i_sql_text dml_utils_data.non_null_text
)
    RETURNS json
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_plan json;
BEGIN
    -- i_sql_text is a non_null_text, so the domain rejects NULL and a blank
    -- statement (the same ASCII whitespace set the rest of the package uses)
    -- before the body runs; the concatenation below therefore cannot produce an
    -- empty statement.

    -- EXPLAIN (FORMAT JSON) returns one row with a single json column. Without
    -- ANALYZE it only plans, so the statement is never executed: an INSERT or
    -- UPDATE yields a ModifyTable plan and changes nothing, and an insert whose
    -- foreign key is unsatisfied still plans. PL/pgSQL's EXECUTE runs every
    -- statement in its argument, so the input is checked first: a
    -- multi-statement string must fail here rather than execute its trailing
    -- statements after the EXPLAIN.
    --
    -- The function is VOLATILE, not STABLE: PostgreSQL rejects EXPLAIN inside a
    -- non-volatile function ("EXPLAIN is not allowed in a non-volatile
    -- function"). It still only plans, so it changes nothing.
    --
    -- The statement is caller-supplied, but the routine is SECURITY INVOKER and
    -- only plans (it cannot execute), so it is no more privileged than running
    -- EXPLAIN on the same SQL directly: it is not an authorization boundary and
    -- needs no provenance check.
    PERFORM dml_utils_lib.assert_single_statement(i_sql_text => i_sql_text);

    EXECUTE 'EXPLAIN (FORMAT JSON) ' || i_sql_text INTO l_plan;

    RETURN l_plan;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.explain_query_plan IS
    'Returns EXPLAIN (FORMAT JSON) of i_sql_text as a json value without '
        'executing it (no ANALYZE), so an INSERT/UPDATE only plans. Rejects a '
        'NULL or empty statement, and rejects a multi-statement string (22023) '
        'so its trailing statements cannot run. The statement is caller-supplied '
        'but the routine is SECURITY INVOKER and only plans, so it is no more '
        'privileged than running EXPLAIN on the same SQL directly.';
