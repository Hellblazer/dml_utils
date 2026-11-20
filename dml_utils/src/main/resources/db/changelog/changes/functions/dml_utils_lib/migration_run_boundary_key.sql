CREATE OR REPLACE FUNCTION dml_utils_lib.migration_run_boundary_key(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS TABLE
            (
                o_key_columns name[],
                o_key_kinds   text[]
            )
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_chunk_by            dml_utils_data.chunking_strategy;
    l_schema_name         text;
    l_table_name          text;
    l_ordered_key_columns name[];
    l_ordered_key_kinds   text[];
    l_key_columns         name[] := ARRAY []::name[];
    l_key_kinds           text[] := ARRAY []::text[];
    l_key                 record;
BEGIN
    SELECT run.chunk_by,
           run.driving_table_schema_name::text,
           run.driving_table_name::text,
           run.ordered_key_columns,
           run.ordered_key_kinds
    INTO l_chunk_by,
        l_schema_name,
        l_table_name,
        l_ordered_key_columns,
        l_ordered_key_kinds
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- A blocks run chunks by ctid and has no key. A partition-wise blocks run
    -- still resolves to no key, even though it has leaves.
    IF l_chunk_by = 'blocks' THEN
        RETURN QUERY SELECT NULL::name[], NULL::text[];
        RETURN;
    END IF;

    -- An ordered-key run applied the descriptor resolved from the parent index
    -- when it started. The same descriptor must drive every leaf; a missing one
    -- is corrupt state, not something to recompute from a leaf.
    IF l_chunk_by = 'ordered_key' THEN
        IF l_ordered_key_columns IS NULL OR l_ordered_key_kinds IS NULL THEN
            RAISE EXCEPTION 'ordered-key run % has no stored key descriptor', i_run_id
                USING ERRCODE = '55000';
        END IF;
        RETURN QUERY SELECT l_ordered_key_columns, l_ordered_key_kinds;
        RETURN;
    END IF;

    -- Primary key: re-read from the live parent on every call. The run also
    -- snapshots the descriptor at creation (primary_key_columns/kinds) so that
    -- resume_migration_run can fail closed (55000) when the live key changed;
    -- reading the live key here keeps the rendered chunk SQL tied to the catalog
    -- the scan actually runs against. Ordered-key runs render from their stored
    -- descriptor instead (above).
    IF l_chunk_by = 'primary_key' THEN
        -- One traversal yields both arrays; reading the columns and the kinds
        -- through two helpers traversed primary_key_attributes twice and repeated
        -- its arity check.
        FOR l_key IN
            SELECT attr.column_name, attr.column_type, attr.key_kind
            FROM dml_utils_lib.primary_key_attributes(
                         i_schema_name => l_schema_name,
                         i_table_name => l_table_name) AS attr
            ORDER BY attr.ordinality
            LOOP
                IF l_key.key_kind IS NULL THEN
                    RAISE EXCEPTION 'primary key column %.%.% has unsupported type %',
                        l_schema_name, l_table_name, l_key.column_name, l_key.column_type
                        USING ERRCODE = '22023';
                END IF;
                l_key_columns := l_key_columns || l_key.column_name;
                l_key_kinds := l_key_kinds || l_key.key_kind;
            END LOOP;

        -- The attributes read and this count share the snapshot, so a mismatch is
        -- a catalog inconsistency, not a concurrent change (55000).
        PERFORM dml_utils_lib.assert_primary_key_arity_matches(
                i_schema_name => l_schema_name,
                i_table_name => l_table_name,
                i_column_count => pg_catalog.cardinality(l_key_columns));

        RETURN QUERY SELECT l_key_columns, l_key_kinds;
        RETURN;
    END IF;

    -- The strategy enum has exactly the three values handled above; this is
    -- defensive so a future value cannot silently fall through to primary-key
    -- semantics.
    RAISE EXCEPTION 'unsupported chunking strategy %', l_chunk_by
        USING ERRCODE = '22023';
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_run_boundary_key IS
    'Returns the boundary-key columns and kinds a run chunks by: NULL for blocks, '
        'the stored ordered-key descriptor for ordered_key, and the driving '
        'parent''s live primary key for primary_key (columns and kinds from one '
        'primary_key_attributes traversal, with the arity check). Every leaf of a '
        'run must use the same key order; a primary-key run snapshots its key at '
        'creation and resume fails closed if the live key changed. An unsupported '
        'strategy is rejected with 22023 rather than falling through.';
