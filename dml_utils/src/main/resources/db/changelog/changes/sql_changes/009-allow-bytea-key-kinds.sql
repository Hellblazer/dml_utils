-- Widens the primary-key and ordered-key shape checks so a run may record bytea
-- as a key kind. Each check is replaced through the lock-aware ddl_utils
-- helpers. The new constraints are added NOT VALID (metadata only, and enforced
-- for new rows at once); the next two changesets validate them.
SELECT ddl_utils.drop_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_primary_key_shape_check'
);

SELECT ddl_utils.add_check_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_primary_key_shape_check',
    i_check_expression => '${primary_key_shape_check_expression}'
);

SELECT ddl_utils.drop_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_ordered_key_shape_check'
);

SELECT ddl_utils.add_check_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_ordered_key_shape_check',
    i_check_expression => '${ordered_key_shape_check_expression}'
);

-- 008 dropped terminal_leaf_for_run on an upgraded database so its changeset could
-- recreate it with the bytea column, and the new function starts without the
-- caller's EXECUTE. 999 runs again only when its own SQL changes, and this
-- feature does not change it; grant it here, after the functions include. On a
-- fresh database this repeats what 999 does later.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.terminal_leaf_for_run(
    dml_utils_data.non_null_bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.terminal_leaf_for_run(
    dml_utils_data.non_null_bigint
    ) TO dml_utils_caller;

COMMENT ON COLUMN dml_utils_data.migration_run.primary_key_kinds IS
    'Boundary kinds matching primary_key_columns position by position (bigint, '
        'text, uuid, timestamptz or bytea); NULL exactly when primary_key_columns is '
        'NULL.';

COMMENT ON COLUMN dml_utils_data.migration_run.ordered_key_kinds IS
    'For chunk_by = ordered_key, the boundary kind of each key column in '
        'ordered_key_columns order (bigint, text, uuid, timestamptz or bytea); NULL '
        'for the other strategies. Immutable.';
