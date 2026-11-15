CREATE OR REPLACE FUNCTION dml_utils.migration_boundaries(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS TABLE
            (
                boundary_no   bigint,
                boundary_id   dml_utils_data.migration_key,
                leaf_ordinal  integer,
                rows_affected bigint,
                started_at    timestamptz,
                completed_at  timestamptz,
                cancelled_at  timestamptz,
                duration_ms   bigint
            )
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
SELECT b.boundary_no,
       b.boundary_id,
       b.leaf_ordinal,
       b.rows_affected,
       b.started_at,
       b.completed_at,
       b.cancelled_at,
       CASE
           WHEN b.started_at IS NULL OR b.completed_at IS NULL THEN NULL
           ELSE (pg_catalog.date_part('epoch', b.completed_at - b.started_at) * 1000)::bigint
           END AS duration_ms
FROM dml_utils_data.migration_boundary AS b
WHERE b.run_id = i_run_id
ORDER BY b.boundary_no;
$$;

COMMENT ON FUNCTION dml_utils.migration_boundaries(dml_utils_data.non_null_bigint) IS
    'Returns the run''s chunk boundaries in order, with the packed key, the '
        'partition leaf for a partition-wise run, the recorded row count, the '
        'start/completion/cancellation timestamps and the chunk duration. A '
        'pending chunk has NULL started_at, completed_at, rows_affected and '
        'duration_ms.';
