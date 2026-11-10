-- Restores the previous check. It is left NOT VALID, which still enforces it
-- for every new row; a rollback cannot run ddl_utils' validating procedure,
-- which commits and so cannot run inside the rollback transaction.
SELECT ddl_utils.drop_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_ordered_key_shape_check'
);

SELECT ddl_utils.add_check_constraint(
    i_schema_name => 'dml_utils_data',
    i_table_name => 'migration_run',
    i_constraint_name => 'migration_run_ordered_key_shape_check',
    i_check_expression => 'ordered_key_columns IS NULL
        OR (pg_catalog.cardinality(ordered_key_columns) BETWEEN 1 AND 3
        AND pg_catalog.cardinality(ordered_key_kinds)
                = pg_catalog.cardinality(ordered_key_columns)
        AND ordered_key_kinds <@ ARRAY [''bigint'', ''text'', ''uuid'', ''timestamptz'']::text[])'
);

COMMENT ON COLUMN dml_utils_data.migration_run.ordered_key_kinds IS
    'For chunk_by = ordered_key, the boundary kind of each key column in '
        'ordered_key_columns order (bigint, text, uuid or timestamptz); NULL for '
        'the other strategies. Immutable.';
