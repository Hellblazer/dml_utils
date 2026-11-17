CREATE OR REPLACE FUNCTION dml_utils_lib.format_boundary_population_sql(
    i_select_list text,
    i_order_by text,
    i_schema_name text,
    i_table_name text,
    i_id_list text,
    i_pack_expression text,
    i_order_by_desc text,
    i_run_id bigint,
    i_chunk_size bigint
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
BEGIN
    -- The two identifiers rendered with %I and the numbers interpolated below are
    -- validated here, like the descriptor's caller does: a NULL argument renders as
    -- an empty string through format(), so a NULL run id, a non-positive chunk size
    -- or a missing fragment would only fail when the statement is finally executed
    -- (after the run and its boundaries exist). A blank ORDER BY is a syntax error.
    -- The fragments are otherwise unvalidated trusted SQL (see the trust boundary
    -- below).
    IF i_run_id IS NULL THEN
        RAISE EXCEPTION 'i_run_id must not be NULL'
            USING ERRCODE = '22023';
    END IF;
    IF i_chunk_size IS NULL OR i_chunk_size <= 0 THEN
        RAISE EXCEPTION 'i_chunk_size must be positive'
            USING ERRCODE = '22023';
    END IF;
    IF i_select_list IS NULL
        OR i_order_by IS NULL
        OR i_schema_name IS NULL
        OR i_table_name IS NULL
        OR i_id_list IS NULL
        OR i_pack_expression IS NULL
        OR i_order_by_desc IS NULL
    THEN
        RAISE EXCEPTION 'the select list, order-by, identifiers, id list and pack expression must not be NULL'
            USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.btrim(i_order_by, E' \t\n\r\f\013') = ''
        OR pg_catalog.btrim(i_order_by_desc, E' \t\n\r\f\013') = ''
    THEN
        RAISE EXCEPTION 'i_order_by and i_order_by_desc must not be blank'
            USING ERRCODE = '22023';
    END IF;

    -- Boundaries are picked by row position, so the computation never needs
    -- min()/max() on the key: PostgreSQL defines those aggregates for the integer
    -- and text types but not for uuid.
    --
    -- The five text fragments (select list, ascending and descending ORDER BY, id
    -- list and pack expression) are interpolated raw, because they are the key
    -- descriptor's own SQL, not identifiers: they must come from
    -- dml_utils_lib.boundary_key_descriptor_sql (which quotes every catalog name)
    -- and never from caller-supplied text. This is the final rendering layer and
    -- it cannot validate fragments without re-parsing SQL, so it documents the
    -- trust boundary instead. Identifiers are the only %I arguments.
    --
    -- The terminal high-water boundary is the last row of the descending scan, so
    -- the contract is: the boundary key is unique, and i_order_by_desc is the exact
    -- inverse of i_order_by (every term reversed, same NULLS ordering). With ties
    -- the row picked by ... DESC LIMIT 1 can differ from the row that is last in
    -- the ascending window order. The descriptor builds both from the primary key
    -- or a unique ordered index, so the precondition holds for every shipped
    -- caller; an ordered key that is not unique would need a tiebreaker added
    -- before this is safe.
    --
    -- The shape matters on production-sized tables:
    --   * numbered is referenced once, so it is inlined and the row_number()
    --     WindowAgg streams over the boundary-key index instead of sorting or
    --     materialising all rows.
    --   * count(*) OVER () is deliberately not used: a full-partition window
    --     aggregate buffers every row (spilling to disk for large tables) just to
    --     recover the row count. The terminal high-water boundary instead comes
    --     from a backward index scan (ORDER BY the key DESC LIMIT 1) and its
    --     boundary_no is the last chunk start plus one.
    --   * only the (N / chunk_size) start rows are materialised.
    RETURN pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils_data.migration_boundary (run_id, boundary_no, boundary_id, completed_at)
        WITH numbered AS (
            SELECT
                %1$s,
                row_number() OVER (ORDER BY %2$s) AS rn
            FROM %3$I.%4$I
        ),
        starts AS MATERIALIZED (
            SELECT
                %5$s,
                (rn - 1) / %9$s::bigint AS boundary_no
            FROM numbered
            WHERE (rn - 1) %% %9$s::bigint = 0
        )
        SELECT
            %8$s,
            boundary_no,
            %6$s AS boundary_id,
            NULL::timestamptz AS completed_at
        FROM starts
        UNION ALL
        SELECT
            %8$s,
            (SELECT max(boundary_no) + 1 FROM starts),
            terminal.boundary_id,
            NULL::timestamptz AS completed_at
        FROM (
            SELECT
                %6$s AS boundary_id
            FROM (
                SELECT %1$s
                FROM %3$I.%4$I
                ORDER BY %7$s
                LIMIT 1
            ) AS last_row
        ) AS terminal
        $chunk$,
            i_select_list,
            i_order_by,
            i_schema_name,
            i_table_name,
            i_id_list,
            i_pack_expression,
            i_order_by_desc,
            i_run_id,
            i_chunk_size);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.format_boundary_population_sql(
    text,
    text,
    text,
    text,
    text,
    text,
    text,
    bigint,
    bigint
    ) IS
    'Returns the single-relation boundary-population statement from the key '
        'descriptor fragments. Raises invalid_parameter_value (22023) for a NULL '
        'run id, a non-positive chunk size, any NULL fragment or identifier, or a '
        'blank ORDER BY; the fragments are otherwise trusted SQL produced by '
        'boundary_key_descriptor_sql. The statement streams the boundary key in '
        'index order, materialises only the chunk starts and takes the terminal from '
        'a backward index scan. The boundary key must be unique with '
        'i_order_by_desc its exact inverse. Shared by build_boundary_population_sql '
        'so execute and explain run the identical SQL.';
