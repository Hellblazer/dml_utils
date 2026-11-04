-- Rollback of the caller grants: revoke what the forward changeset granted
-- and restore PostgreSQL's defaults. Mirrors 999-grant-privileges.sql.
REVOKE USAGE ON SCHEMA dml_utils FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils_lib FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils_data FROM dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils_data.non_null_text TO public;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_text FROM dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils_data.positive_integer TO public;
REVOKE USAGE ON DOMAIN dml_utils_data.positive_integer FROM dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils_data.non_negative_integer TO public;
REVOKE USAGE ON DOMAIN dml_utils_data.non_negative_integer FROM dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils_data.non_null_boolean TO public;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_boolean FROM dml_utils_caller;
GRANT USAGE ON TYPE dml_utils_data.migration_key TO public;
REVOKE USAGE ON TYPE dml_utils_data.migration_key FROM dml_utils_caller;
GRANT USAGE ON TYPE dml_utils_data.chunking_strategy TO public;
REVOKE USAGE ON TYPE dml_utils_data.chunking_strategy FROM dml_utils_caller;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils
    GRANT EXECUTE ON FUNCTIONS TO public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_lib
    GRANT EXECUTE ON FUNCTIONS TO public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_data
    GRANT EXECUTE ON FUNCTIONS TO public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_data
    GRANT USAGE ON TYPES TO public;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_run FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_boundary FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_error FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_run_leaf FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_data.set_updated_at() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.reject_migration_run_update() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.reject_migration_boundary_update() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.check_migration_run_leaf() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.check_migration_boundary_leaf() TO public;

REVOKE EXECUTE ON FUNCTION dml_utils_lib.claim_migration_boundary(bigint, bigint)
    FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.claim_migration_boundary(bigint, bigint)
    TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.complete_migration_boundary(bigint, bigint, timestamptz)
    FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.complete_migration_boundary(bigint, bigint, timestamptz)
    TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.complete_migration_run(bigint)
    FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.complete_migration_run(bigint)
    TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.mark_migration_run_cancelled(bigint)
    FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.mark_migration_run_cancelled(bigint)
    TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_plain_heap(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_plain_heap(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.leaf_partitions(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.leaf_partitions(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.primary_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.primary_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.primary_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.primary_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.primary_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.primary_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_usable_ordered_key(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_usable_ordered_key(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_attributes_for_index(
    oid,
    text,
    text,
    text
    ) FROM dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_leaf_indexes(oid) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_leaf_indexes(oid) TO public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_attributes_for_index(
    oid,
    text,
    text,
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.partitioned_ordered_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.partitioned_ordered_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.partitioned_ordered_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.partitioned_ordered_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.partitioned_ordered_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.partitioned_ordered_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_ordered_key_leaves(
    bigint,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_ordered_key_leaves(
    bigint,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_boundary_key_index(
    dml_utils_data.chunking_strategy,
    name
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_boundary_key_index(
    dml_utils_data.chunking_strategy,
    name
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_key_values(
    dml_utils_data.migration_key,
    text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_key_values(
    dml_utils_data.migration_key,
    text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_key_pair_values(
    dml_utils_data.migration_key,
    dml_utils_data.migration_key,
    text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_key_pair_values(
    dml_utils_data.migration_key,
    dml_utils_data.migration_key,
    text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_key_is_canonical(
    dml_utils_data.migration_key
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_key_is_canonical(
    dml_utils_data.migration_key
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_function_chunk_template(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_function_chunk_template(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.resolve_migration_run(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.resolve_migration_run(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.prepare_chunk_sql_render(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.prepare_chunk_sql_render(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_prepared_chunk_sql(
    text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    text[],
    text[],
    text[],
    boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_prepared_chunk_sql(
    text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    text[],
    text[],
    text[],
    boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    text[],
    text[],
    boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    text[],
    text[],
    boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.populate_migration_boundaries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.populate_migration_boundaries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_run_calculation_lock_key(bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_run_calculation_lock_key(bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_migration_tree_unchanged(bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_migration_tree_unchanged(bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_run_boundaries(bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_run_boundaries(bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.record_migration_leaf_error(
    bigint,
    integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.record_migration_leaf_error(
    bigint,
    integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.process_migration_chunk(
    bigint,
    bigint,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.process_migration_chunk(
    bigint,
    bigint,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.record_migration_error(
    bigint,
    bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.record_migration_error(
    bigint,
    bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.boundary_key_descriptor_sql(
    name[],
    text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.boundary_key_descriptor_sql(
    name[],
    text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    bigint,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    bigint,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_block_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_block_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_run_boundary_key(bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_run_boundary_key(bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_migration_leaf_unchanged(
    bigint,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_migration_leaf_unchanged(
    bigint,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.leaf_terminal_boundary_id(
    bigint,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.leaf_terminal_boundary_id(
    bigint,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_leaf(bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_leaf(bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.finalize_migration_boundaries(bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.finalize_migration_boundaries(bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_block_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_block_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.synthetic_chunk_boundary_values(
    text[],
    bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.synthetic_chunk_boundary_values(
    text[],
    bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_query_plan(
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_query_plan(
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    dml_utils_data.chunking_strategy
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    dml_utils_data.chunking_strategy
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plan(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    text[],
    text[],
    boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plan(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    text[],
    text[],
    boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.run_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.run_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.run_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    text,
    text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.run_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    text,
    text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_negative_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_migration_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    boolean,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_migration_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    boolean,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    text,
    dml_utils_data.chunking_strategy
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    text,
    dml_utils_data.chunking_strategy
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_function_over_table_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    boolean,
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_function_over_table_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    boolean,
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_function(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_function(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_sql_text(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_sql_text(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_threads(
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_threads(
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_sleep_ms(
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_sleep_ms(
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.cancel_migration_run(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.cancel_migration_run(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs() FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs() TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_run_summary(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_run_summary(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_errors(
    bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_errors(
    bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_boundaries(
    bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_boundaries(
    bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_key_render_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_key_render_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_chunk_boundary_literals(
    text[], text[], text[], text[], text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_chunk_boundary_literals(
    text[], text[], text[], text[], text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_chunk_range_boxes(
    text[], text[], text[], integer, boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_chunk_range_boxes(
    text[], text[], text[], integer, boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.boundary_key_pack_expression(text[])
    FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.boundary_key_pack_expression(text[])
    TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.format_boundary_population_sql(
    text, text, text, text, text, text, text, bigint, bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.format_boundary_population_sql(
    text, text, text, text, text, text, text, bigint, bigint
    ) TO public;
