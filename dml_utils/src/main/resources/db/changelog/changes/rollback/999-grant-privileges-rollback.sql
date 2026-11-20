-- Rollback of the caller grants: revoke what the forward changeset granted
-- and restore PostgreSQL's defaults. Mirrors 999-grant-privileges.sql.
REVOKE USAGE ON SCHEMA dml_utils FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils_lib FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils_data FROM dml_utils_caller;
-- 007-default-privileges runs before 004 and uses a database-wide default ACL, so
-- the domains and the explicitly created types below exist without PUBLIC usage:
-- the forward PUBLIC revokes are no-ops and the pre-999 state is "no PUBLIC
-- access". Take the caller's grant back only; granting USAGE to PUBLIC would
-- widen access beyond what a fresh install ever had.
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_text FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.positive_integer FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_negative_integer FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_boolean FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_bigint FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_negative_bigint FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.positive_bigint FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_timestamptz FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_oid FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_element_integer_array FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_element_bigint_array FROM dml_utils_caller;
REVOKE USAGE ON TYPE dml_utils_data.migration_key FROM dml_utils_caller;
REVOKE USAGE ON TYPE dml_utils_data.chunking_strategy FROM dml_utils_caller;
-- The four migration table row types are the exception: PostgreSQL does not apply
-- default ACLs to the implicit composite type of a table, so PUBLIC really does
-- have the built-in USAGE before 999. Restore it here, alongside the caller
-- revoke.
GRANT USAGE ON TYPE dml_utils_data.migration_run TO public;
REVOKE USAGE ON TYPE dml_utils_data.migration_run FROM dml_utils_caller;
GRANT USAGE ON TYPE dml_utils_data.migration_boundary TO public;
REVOKE USAGE ON TYPE dml_utils_data.migration_boundary FROM dml_utils_caller;
GRANT USAGE ON TYPE dml_utils_data.migration_error TO public;
REVOKE USAGE ON TYPE dml_utils_data.migration_error FROM dml_utils_caller;
GRANT USAGE ON TYPE dml_utils_data.migration_run_leaf TO public;
REVOKE USAGE ON TYPE dml_utils_data.migration_run_leaf FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_run FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_boundary FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_error FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_run_leaf FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_data.set_updated_at() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.reject_migration_run_update() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.reject_migration_boundary_update() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.check_migration_run_leaf() TO public;
GRANT EXECUTE ON FUNCTION dml_utils_data.check_migration_boundary_leaf() TO public;

REVOKE EXECUTE ON FUNCTION dml_utils_lib.claim_migration_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.claim_migration_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.complete_migration_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_timestamptz,
    dml_utils_data.non_negative_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.complete_migration_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_timestamptz,
    dml_utils_data.non_negative_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.complete_migration_run(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.complete_migration_run(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.mark_migration_run_cancelled(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.mark_migration_run_cancelled(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(dml_utils_data.non_null_text) TO public;
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
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_primary_key_arity_matches(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_primary_key_arity_matches(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer
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
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_leaf_indexes(dml_utils_data.non_null_oid) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_leaf_indexes(dml_utils_data.non_null_oid) TO public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_attributes_for_index(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
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
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_ordered_key_leaves(
    dml_utils_data.non_null_bigint,
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
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_key_is_canonical(dml_utils_data.migration_key) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_key_is_canonical(dml_utils_data.migration_key) TO public;
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
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_single_statement(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_single_statement(dml_utils_data.non_null_text) TO public;
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
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
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    text[],
    text[],
    text[],
    dml_utils_data.non_null_boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_prepared_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    text[],
    text[],
    text[],
    dml_utils_data.non_null_boolean
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
    dml_utils_data.non_null_boolean
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
    dml_utils_data.non_null_boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_threads_within_max_worker_processes(
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_threads_within_max_worker_processes(
    dml_utils_data.positive_integer
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_run_calculation_lock_key(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_run_calculation_lock_key(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_migration_tree_unchanged(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_migration_tree_unchanged(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_run_boundaries(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_run_boundaries(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.record_migration_leaf_error(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.record_migration_leaf_error(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.process_migration_chunk(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.process_migration_chunk(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.record_migration_error(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.record_migration_error(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.boundary_key_descriptor_sql(name[], text[]) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.boundary_key_descriptor_sql(name[], text[]) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_block_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_block_leaf_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_run_boundary_key(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_run_boundary_key(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_migration_leaf_unchanged(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_migration_leaf_unchanged(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.leaf_terminal_boundary_id(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.leaf_terminal_boundary_id(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_leaf(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.calculate_migration_leaf(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.finalize_migration_boundaries(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.finalize_migration_boundaries(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_block_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_negative_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_block_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_bigint,
    dml_utils_data.non_negative_bigint
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
    dml_utils_data.positive_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.synthetic_chunk_boundary_values(
    text[],
    dml_utils_data.positive_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_query_plan(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_query_plan(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.positive_bigint,
    dml_utils_data.chunking_strategy
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.positive_bigint,
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
    dml_utils_data.non_null_boolean
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
    dml_utils_data.non_null_boolean
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
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
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
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
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_migration_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_migration_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_migration_run_chunk(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_migration_run_chunk(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_function_over_table_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.explain_function_over_table_chunk(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text[],
    text[],
    dml_utils_data.non_null_boolean,
    text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean,
    integer
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
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_retries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_retries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.archive_migration_run(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.archive_migration_run(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.cancel_migration_run(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.cancel_migration_run(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.pause_migration_run(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.pause_migration_run(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.resume_migration_run(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.resume_migration_run(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs(
    text,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs(
    text,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_run_summary(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_run_summary(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_run_leaves(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_run_leaves(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_run_progress(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_run_progress(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_errors(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_errors(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_boundaries(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.migration_boundaries(dml_utils_data.non_null_bigint) TO public;
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
    text[],
    text[],
    text[],
    text[],
    text[]
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_chunk_boundary_literals(
    text[],
    text[],
    text[],
    text[],
    text[]
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_chunk_range_boxes(
    text[],
    text[],
    text[],
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_null_boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_chunk_range_boxes(
    text[],
    text[],
    text[],
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_null_boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.boundary_key_pack_expression(text[]) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.boundary_key_pack_expression(text[]) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.format_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.format_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_bigint
    ) TO public;

-- Ordered-key and partition introspection helpers.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_ordered_key_index_usable(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_ordered_key_index_usable(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.ordered_key_column_attributes(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.ordered_key_column_attributes(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.resolve_partitioned_ordered_key_index(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.resolve_partitioned_ordered_key_index(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_ordered_key_child_indexes(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_ordered_key_child_indexes(
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_leaf_relations(
    dml_utils_data.non_null_oid
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_leaf_relations(
    dml_utils_data.non_null_oid
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_leaf_privileges(
    dml_utils_data.non_null_oid
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_partitioned_leaf_privileges(
    dml_utils_data.non_null_oid
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.manifest_leaf_identity(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.manifest_leaf_identity(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_live_leaf_matches_manifest(
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_live_leaf_matches_manifest(
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_oid,
    dml_utils_data.non_null_text,
    text
    ) TO public;

-- Function-template and explain helpers.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.resolve_function_chunk_target(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.resolve_function_chunk_target(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_block_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_block_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_key_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.positive_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_key_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    dml_utils_data.positive_bigint
    ) TO public;

-- Leaf calculation and finalization helpers.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.claim_next_migration_leaf(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.claim_next_migration_leaf(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.calculate_next_leaf_in_worker(
    dml_utils_data.non_null_bigint
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.calculate_next_leaf_in_worker(
    dml_utils_data.non_null_bigint
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.finalize_calculated_run(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.finalize_calculated_run(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.terminal_leaf_for_run(dml_utils_data.non_null_bigint) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.terminal_leaf_for_run(dml_utils_data.non_null_bigint) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.append_terminal_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.append_terminal_boundary(
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) TO public;

-- Leaf calculation SQL builder.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.leaf_boundary_population_sql(
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.leaf_boundary_population_sql(
    dml_utils_data.chunking_strategy,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_bigint,
    dml_utils_data.positive_integer
    ) TO public;

-- Run creation and resolution helpers.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_caller_can_select_relation(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_caller_can_select_relation(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.resolve_migration_creation_key(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.resolve_migration_creation_key(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name,
    dml_utils_data.non_null_boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.snapshot_partition_leaf_manifest(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.snapshot_partition_leaf_manifest(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.populate_single_relation_boundaries(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name[],
    text[],
    dml_utils_data.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.populate_single_relation_boundaries(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    name[],
    text[],
    dml_utils_data.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.find_active_migration_run(dml_utils_data.non_null_text) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.find_active_migration_run(dml_utils_data.non_null_text) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.resume_migration_run(
    dml_utils_data.migration_run,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.resume_migration_run(
    dml_utils_data.migration_run,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.create_migration_run(
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.create_migration_run(
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
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) TO public;

-- Chunk-runner coordinator helpers.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.prepare_partition_wise_execution(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.prepare_partition_wise_execution(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.next_migration_chunk(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_boolean,
    text[],
    bigint[],
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.next_migration_chunk(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_null_boolean,
    text[],
    bigint[],
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.record_migration_worker_failure(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    text,
    text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.record_migration_worker_failure(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_bigint,
    text,
    text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.sleep_between_migration_chunks(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.sleep_between_migration_chunks(
    dml_utils_data.non_null_bigint,
    dml_utils_data.non_negative_integer
    ) TO public;

-- Worker stop/drain helper.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.stop_migration_workers(
    dml_utils_data.non_null_element_integer_array,
    dml_utils_data.non_null_element_bigint_array,
    dml_utils_data.non_null_boolean,
    integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.stop_migration_workers(
    dml_utils_data.non_null_element_integer_array,
    dml_utils_data.non_null_element_bigint_array,
    dml_utils_data.non_null_boolean,
    integer
    ) TO public;
