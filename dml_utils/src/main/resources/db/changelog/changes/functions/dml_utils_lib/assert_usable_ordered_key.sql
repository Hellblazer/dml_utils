CREATE OR REPLACE FUNCTION dml_utils_lib.assert_usable_ordered_key(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_index_name dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- Validation lives in ordered_key_attributes, so the run path and the
    -- resume check cannot drift. Reading (and discarding) its rows runs every
    -- check.
    PERFORM 1
    FROM dml_utils_lib.ordered_key_attributes(
                 i_schema_name => i_schema_name,
                 i_table_name => i_table_name,
                 i_index_name => i_index_name) AS a;

    -- An assert gate must enforce its own contract: a future refactor that made
    -- the callee filter instead of raise would otherwise turn this into a no-op.
    IF NOT FOUND THEN
        RAISE EXCEPTION 'index % on %.% has no usable ordered-key attributes',
            i_index_name, i_schema_name, i_table_name
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_usable_ordered_key IS
    'Raises when the named index cannot serve as an ordered key, delegating to '
        'ordered_key_attributes. Returns normally when it can; raises '
        'object_not_in_prerequisite_state (55000) if the callee returns no '
        'attributes. Used by the run path and by resume validation.';
