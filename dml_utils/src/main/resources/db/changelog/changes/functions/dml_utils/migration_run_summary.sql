CREATE OR REPLACE FUNCTION dml_utils.migration_run_summary(
    i_label dml_utils_data.non_null_text
)
    RETURNS TABLE
            (
                run_id                          bigint,
                label                           text,
                chunk_size                      integer,
                chunk_by                        text,
                threads                         integer,
                sleep_ms                        integer,
                driving_table_schema_name       text,
                driving_table_name              text,
                driving_table_relation_filepath text,
                created_at                      timestamptz,
                started_at                      timestamptz,
                boundaries_calculated_at        timestamptz,
                completed_at                    timestamptz,
                cancelled_at                    timestamptz,
                archived_at                     timestamptz,
                boundary_count                  bigint,
                completed_boundary_count        bigint,
                error_count                     bigint,
                partition_wise                  boolean,
                total_chunks                    bigint,
                total_chunks_completed          bigint,
                leaf_count                      bigint,
                leaves_calculated               bigint,
                leaves_failed                   bigint,
                leaves_pending                  bigint
            )
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
SELECT r.run_id,
       r.label,
       r.chunk_size,
       r.chunk_by::text,
       r.threads,
       r.sleep_ms,
       r.driving_table_schema_name::text,
       r.driving_table_name::text,
       r.driving_table_relation_filepath,
       r.created_at,
       r.started_at,
       r.boundaries_calculated_at,
       r.completed_at,
       r.cancelled_at,
       r.archived_at,
       pg_catalog.count(b.boundary_no)     AS boundary_count,
       pg_catalog.count(b.completed_at)    AS completed_boundary_count,
       (SELECT pg_catalog.count(*)
        FROM dml_utils_data.migration_error AS e
        WHERE e.run_id = r.run_id)::bigint AS error_count,
       r.partition_wise,
       r.total_chunks,
       r.total_chunks_completed,
       leaf.leaf_count,
       leaf.leaves_calculated,
       leaf.leaves_failed,
       leaf.leaves_pending
FROM dml_utils_data.migration_run AS r
         LEFT JOIN dml_utils_data.migration_boundary AS b ON b.run_id = r.run_id
         LEFT JOIN LATERAL (
    SELECT pg_catalog.count(*)                                                      AS leaf_count,
           pg_catalog.count(*) FILTER (WHERE l.state = 'calculated')                AS leaves_calculated,
           pg_catalog.count(*) FILTER (WHERE l.state = 'failed')                    AS leaves_failed,
           pg_catalog.count(*) FILTER (WHERE l.state IN ('pending', 'calculating')) AS leaves_pending
    FROM dml_utils_data.migration_run_leaf AS l
    WHERE l.run_id = r.run_id
    ) AS leaf ON TRUE
WHERE r.label = i_label
GROUP BY r.run_id, leaf.leaf_count, leaf.leaves_calculated, leaf.leaves_failed, leaf.leaves_pending
ORDER BY r.run_id;
$$;

COMMENT ON FUNCTION dml_utils.migration_run_summary IS
    'Returns one high-level row per run for the label, with its boundary and '
        'error counts, whether the run is partition-wise, the stored chunk '
        'totals, and the leaf counts. leaves_pending includes the leaves still '
        'to calculate and any claim left behind by a failed worker.';
