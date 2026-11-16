CREATE OR REPLACE FUNCTION dml_utils_lib.find_active_migration_run(
    i_label dml_utils_data.non_null_text
)
    RETURNS SETOF dml_utils_data.migration_run
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
    -- Select the whole row, not a copied column list: the function's result type
    -- is the table composite, so this cannot drift when a column is added. (Not a
    -- `*` projection: the row reference is a single composite value.)
SELECT run
FROM dml_utils_data.migration_run AS run
WHERE run.label = i_label
  AND run.archived_at IS NULL;
$$;

COMMENT ON FUNCTION dml_utils_lib.find_active_migration_run(
    dml_utils_data.non_null_text
    ) IS
    'Returns the active (not archived) run for a label, or no row, as the '
        'migration_run composite itself so the projection cannot drift from the '
        'table. Used by resolve_migration_run to decide between resume and '
        'creation.';
