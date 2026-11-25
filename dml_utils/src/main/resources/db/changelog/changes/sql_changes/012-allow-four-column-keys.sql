-- Widens the primary-key and ordered-key shape checks so a run may record a key
-- of up to four columns (they allowed three). Each check is replaced through the
-- lock-aware ddl_utils helpers, as in 009. The new constraints are added NOT
-- VALID (metadata only, and enforced for new rows at once); the next two
-- changesets validate them.
SELECT ddl_utils.drop_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_primary_key_shape_check'
);

SELECT ddl_utils.add_check_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_primary_key_shape_check',
    i_check_expression => '${primary_key_shape_check_expression_four_columns}'
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
    i_check_expression => '${ordered_key_shape_check_expression_four_columns}'
);
