CREATE OR REPLACE FUNCTION dml_utils_lib.find_terminal_migration_run(
    i_label dml_utils_data.non_null_text
)
    RETURNS SETOF dml_utils_data.migration_run
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
    -- Select the whole row, not a copied column list: the function's result type
    -- is the table composite, so this cannot drift when a column is added.
    --
    -- A terminal run is completed or cancelled but not archived. The resolver
    -- checks this before the live-run lookup: completed is a no-op, cancelled is
    -- a fail-closed 55000, and neither may be resumed.
SELECT run
FROM dml_utils_data.migration_run AS run
WHERE run.label = i_label
  AND run.archived_at IS NULL
  AND (run.completed_at IS NOT NULL OR run.cancelled_at IS NOT NULL);
$$;

COMMENT ON FUNCTION dml_utils_lib.find_terminal_migration_run IS
    'Returns the terminal (completed or cancelled) non-archived run for a label, '
        'or no row, as the migration_run composite itself. Used by '
        'resolve_migration_run to report completion or reject cancellation before '
        'the live-run lookup.';
