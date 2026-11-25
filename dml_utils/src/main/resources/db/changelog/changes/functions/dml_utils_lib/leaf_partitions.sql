CREATE OR REPLACE FUNCTION dml_utils_lib.leaf_partitions(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy
)
    RETURNS TABLE
            (
                leaf_ordinal      integer,
                leaf_schema_name  text,
                leaf_name         text,
                leaf_oid          oid,
                leaf_bound        text,
                relation_filepath text
            )
    LANGUAGE plpgsql
    SECURITY INVOKER
    -- The manifest order sorts the deparsed partition bounds byte-wise. Those
    -- expressions depend on TimeZone for timestamptz bounds, DateStyle for
    -- date/timestamp bounds, IntervalStyle for interval bounds,
    -- extra_float_digits for float bounds, standard_conforming_strings for the
    -- escaping of string constants, bytea_output for bytea constants and
    -- lc_monetary for money constants, so pin them: the same tree must yield the
    -- same ordinals and the same stored text regardless of the caller.
    SET timezone = 'UTC'
    SET datestyle = 'ISO, MDY'
    SET intervalstyle = 'postgres'
    SET extra_float_digits = 0
    SET standard_conforming_strings = on
    SET bytea_output = 'hex'
    SET lc_monetary = 'C'

AS
$$
DECLARE
    l_parent oid;
BEGIN
    -- The strategy enum deliberately has no domain (a domain over it breaks
    -- jOOQ's generated class initialisation), so NULL is rejected explicitly:
    -- otherwise the filepath CASE below falls through to NULL for every leaf and
    -- the call silently degrades to a key-strategy result.
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_partitioned_table(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    SELECT c.oid
    INTO l_parent
    FROM pg_catalog.pg_class AS c
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE n.nspname = i_schema_name::name
      AND c.relname = i_table_name::name;

    -- READ COMMITTED gives each statement a fresh snapshot. A concurrent DROP
    -- between assert_partitioned_table and this lookup leaves no row; raise the
    -- same missing-relation error instead of asserting leaf properties against
    -- a NULL parent.
    IF l_parent IS NULL THEN
        RAISE EXCEPTION 'relation %.% does not exist', i_schema_name, i_table_name
            USING ERRCODE = '42P01';
    END IF;

    -- The assertions and the enumeration are one SQL statement, so they share a
    -- snapshot: a leaf attached between the checks and the result cannot be
    -- returned unvalidated. The CTE is MATERIALIZED so the assertions run once,
    -- not once per enumerated row.
    --
    -- The order is the manifest contract: partition bound, then schema, name and
    -- OID, all byte-wise, so every enumeration of the same tree yields the same
    -- ordinals. A leaf with no rows still gets a row: emptiness is a calculation
    -- result, not a reason to drop it from the snapshot.
    RETURN QUERY
        WITH validated AS MATERIALIZED (SELECT dml_utils_lib.assert_partitioned_leaf_relations(
                                                       i_parent => l_parent) AS relations,
                                               dml_utils_lib.assert_partitioned_leaf_privileges(
                                                       i_parent => l_parent) AS privileges)
        SELECT (pg_catalog.row_number() OVER (
            ORDER BY pg_catalog.pg_get_expr(c.relpartbound, c.oid) COLLATE pg_catalog."C",
                n.nspname::text COLLATE pg_catalog."C",
                c.relname::text COLLATE pg_catalog."C",
                c.oid))::integer,
               n.nspname::text,
               c.relname::text,
               c.oid,
               pg_catalog.pg_get_expr(c.relpartbound, c.oid),
               CASE
                   WHEN i_chunk_by = 'blocks'
                       THEN pg_catalog.pg_relation_filepath(c.oid)
                   END
        FROM pg_catalog.pg_partition_tree(l_parent) AS tree
                 JOIN pg_catalog.pg_class AS c ON c.oid = tree.relid
                 JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
                 CROSS JOIN validated
        WHERE tree.isleaf
        ORDER BY 1;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.leaf_partitions IS
    'Lists the storage leaves of a partitioned table in the deterministic '
        'manifest order (partition bound, schema, name, OID, byte-wise), with '
        'their OIDs, partition-bound expressions and, for chunk_by = blocks, '
        'physical relation filepaths. The deparsed bounds are rendered with '
        'pinned TimeZone/DateStyle/IntervalStyle/extra_float_digits so the '
        'manifest text is independent of the caller''s session. A NULL strategy '
        'is rejected (22023). assert_partitioned_leaf_relations rejects a '
        'non-table leaf (42809) and assert_partitioned_leaf_privileges rejects a '
        'leaf the caller cannot read (42501). Every leaf is returned, including '
        'one with no rows.';
