CREATE OR REPLACE FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    i_label dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- unique_violation is the same SQLSTATE the partial unique index on (label)
    -- raises, so a caller sees one code whether this check or the index wins.
    -- This check is advisory (check-then-act): under concurrency its correctness
    -- depends on migration_run_label_active_idx, which is what actually prevents
    -- two active runs for one label.
    IF EXISTS (SELECT 1
               FROM dml_utils_data.migration_run
               WHERE label = i_label
                 AND archived_at IS NULL)
    THEN
        RAISE EXCEPTION 'an active run for label % already exists', i_label
            USING ERRCODE = '23505';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_no_active_run_for_label IS
    'Raises unique_violation (23505) when a not-archived migration run already '
        'exists for the label.';
