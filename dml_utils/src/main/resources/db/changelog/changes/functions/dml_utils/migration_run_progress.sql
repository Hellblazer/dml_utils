CREATE OR REPLACE FUNCTION dml_utils.migration_run_progress(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS TABLE
            (
                run_id                   bigint,
                label                    text,
                chunk_by                 text,
                partition_wise           boolean,
                status                   text,
                total_chunks             bigint,
                completed_chunks         bigint,
                remaining_chunks         bigint,
                percent_complete         double precision,
                total_rows_affected      bigint,
                elapsed_ms               bigint,
                chunks_per_second        double precision,
                rows_per_second          double precision,
                estimated_remaining_ms   bigint,
                estimated_completion_at  timestamptz,
                paused_at                timestamptz,
                max_retries              integer,
                retry_backoff_ms         integer,
                started_at               timestamptz,
                boundaries_calculated_at timestamptz,
                completed_at             timestamptz,
                cancelled_at             timestamptz,
                archived_at              timestamptz
            )
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
WITH base AS (SELECT r.run_id,
                     r.label,
                     r.chunk_by::text                          AS chunk_by,
                     r.partition_wise,
                     CASE
                         WHEN r.archived_at IS NOT NULL THEN 'archived'
                         WHEN r.cancelled_at IS NOT NULL THEN 'cancelled'
                         WHEN r.completed_at IS NOT NULL THEN 'completed'
                         WHEN r.paused_at IS NOT NULL THEN 'paused'
                         WHEN r.boundaries_calculated_at IS NULL THEN 'calculating'
                         ELSE 'active'
                         END                                   AS status,
                     r.total_chunks,
                     r.total_chunks_completed,
                     r.total_chunks - r.total_chunks_completed AS remaining_chunks,
                     r.total_rows_affected,
                     -- Wall-clock time since the start, up to the terminal
                     -- timestamp or now while the run is unfinished.
                     CASE
                         WHEN r.started_at IS NULL THEN NULL
                         ELSE (pg_catalog.date_part('epoch', COALESCE(r.completed_at,
                                                                      r.cancelled_at,
                                                                      pg_catalog.now())
                             - r.started_at) * 1000)::bigint
                         END                                   AS elapsed_ms,
                     r.paused_at,
                     r.max_retries,
                     r.retry_backoff_ms,
                     r.started_at,
                     r.boundaries_calculated_at,
                     r.completed_at,
                     r.cancelled_at,
                     r.archived_at
              FROM dml_utils_data.migration_run AS r
              WHERE r.run_id = i_run_id),
     rates AS (SELECT b.run_id,
                      b.label,
                      b.chunk_by,
                      b.partition_wise,
                      b.status,
                      b.total_chunks,
                      b.total_chunks_completed,
                      b.remaining_chunks,
                      b.total_rows_affected,
                      b.elapsed_ms,
                      b.paused_at,
                      b.max_retries,
                      b.retry_backoff_ms,
                      b.started_at,
                      b.boundaries_calculated_at,
                      b.completed_at,
                      b.cancelled_at,
                      b.archived_at,
                      CASE
                          WHEN b.boundaries_calculated_at IS NOT NULL
                              AND b.total_chunks_completed > 0
                              AND b.elapsed_ms > 0
                              THEN b.total_chunks_completed::double precision
                              / (b.elapsed_ms / 1000.0)
                          END AS chunks_per_second,
                      CASE
                          WHEN b.boundaries_calculated_at IS NOT NULL
                              AND b.total_chunks_completed > 0
                              AND b.elapsed_ms > 0
                              THEN b.total_rows_affected::double precision
                              / (b.elapsed_ms / 1000.0)
                          END AS rows_per_second
               FROM base AS b),
     computed AS (SELECT r.run_id,
                         r.label,
                         r.chunk_by,
                         r.partition_wise,
                         r.status,
                         r.total_chunks,
                         r.total_chunks_completed,
                         r.remaining_chunks,
                         r.total_rows_affected,
                         r.elapsed_ms,
                         r.chunks_per_second,
                         r.rows_per_second,
                         r.paused_at,
                         r.max_retries,
                         r.retry_backoff_ms,
                         r.started_at,
                         r.boundaries_calculated_at,
                         r.completed_at,
                         r.cancelled_at,
                         r.archived_at,
                         CASE
                             WHEN r.total_chunks = 0
                                 THEN CASE
                                          WHEN r.boundaries_calculated_at IS NULL THEN 0.0
                                          ELSE 100.0
                                 END
                             ELSE pg_catalog.round(
                                     100.0 * r.total_chunks_completed / r.total_chunks,
                                     2)::double precision
                             END AS percent_complete,
                         CASE
                             WHEN r.boundaries_calculated_at IS NULL THEN NULL
                             WHEN r.remaining_chunks = 0 THEN 0::bigint
                             WHEN r.status = 'active' AND r.chunks_per_second > 0
                                 THEN pg_catalog.ceil(
                                     r.remaining_chunks * 1000.0 / r.chunks_per_second)::bigint
                             END AS estimated_remaining_ms
                  FROM rates AS r)
SELECT c.run_id,
       c.label,
       c.chunk_by,
       c.partition_wise,
       c.status,
       c.total_chunks,
       c.total_chunks_completed AS completed_chunks,
       c.remaining_chunks,
       c.percent_complete,
       c.total_rows_affected,
       c.elapsed_ms,
       c.chunks_per_second,
       c.rows_per_second,
       c.estimated_remaining_ms,
       CASE
           WHEN c.estimated_remaining_ms IS NULL THEN NULL
           WHEN c.remaining_chunks = 0 THEN COALESCE(c.completed_at, c.cancelled_at, pg_catalog.now())
           ELSE pg_catalog.now()
               + pg_catalog.make_interval(secs => c.estimated_remaining_ms / 1000.0)
           END                  AS estimated_completion_at,
       c.paused_at,
       c.max_retries,
       c.retry_backoff_ms,
       c.started_at,
       c.boundaries_calculated_at,
       c.completed_at,
       c.cancelled_at,
       c.archived_at
FROM computed AS c;
$$;

COMMENT ON FUNCTION dml_utils.migration_run_progress(dml_utils_data.non_null_bigint) IS
    'Returns one progress row for a run: derived status, chunk and row counts, '
        'percent complete, elapsed time, throughput rates and an estimated finish. '
        'The rates are NULL until the boundaries are calculated and at least one '
        'chunk has completed; the estimate is NULL while work remains and the run '
        'is calculating, paused or cancelled, and is 0 with a terminal completion '
        'time when no chunks remain. An empty calculated run reports 100%.';
