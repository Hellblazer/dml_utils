CREATE OR REPLACE FUNCTION dml_utils.delete_archived_migration_runs(
    -- NULL deletes for every label; a value restricts the batch to that label.
    i_label text DEFAULT NULL,
    i_limit dml_utils_data.positive_integer DEFAULT 1000
)
    RETURNS bigint
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
WITH candidates AS (SELECT run.run_id
                    FROM dml_utils_data.migration_run AS run
                    WHERE run.archived_at IS NOT NULL
                      AND (run.completed_at IS NOT NULL OR run.cancelled_at IS NOT NULL)
                      AND (i_label IS NULL OR run.label = i_label)
                      -- A completed run has no in-flight worker: the coordinator completes it only
                      -- after every chunk finished. A cancelled run can still have a pending
                      -- boundary, because cancel_migration_run marks every unclaimed one: a
                      -- pending boundary there is one a worker had claimed, and the coordinator
                      -- marks it after stopping the worker. Skip the run until then, instead of
                      -- cascading into rows that worker may still lock.
                      AND (run.completed_at IS NOT NULL
                        OR NOT EXISTS (SELECT 1
                                       FROM dml_utils_data.migration_boundary AS boundary
                                       WHERE boundary.run_id = run.run_id
                                         AND boundary.completed_at IS NULL
                                         AND boundary.cancelled_at IS NULL))
                    ORDER BY run.run_id
                    LIMIT i_limit FOR UPDATE SKIP LOCKED),
     deleted AS (
         DELETE FROM dml_utils_data.migration_run AS run
             USING candidates
             WHERE run.run_id = candidates.run_id
             RETURNING 1)
SELECT pg_catalog.count(*)::bigint
FROM deleted;
$$;

COMMENT ON FUNCTION dml_utils.delete_archived_migration_runs IS
    'Deletes up to i_limit terminal (completed or cancelled) archived migration '
        'runs - all of them, or only those for i_label - with their boundaries, '
        'errors and leaf manifest, returning the number of runs deleted. Call it in '
        'a loop until it returns 0 to clean up incrementally: the batch bounds the '
        'transaction, not one run''s cascade. A cancelled run that still has a '
        'pending (neither completed nor cancelled) boundary is skipped because a '
        'worker may still be winding it down (cancel_migration_run leaves exactly '
        'those boundaries pending); a completed run has no in-flight worker. A run '
        'row locked by another session is skipped, so a return of 0 also means '
        '"every remaining candidate is locked", not only "drained"; retry later. If '
        'a coordinator died between cancelling and marking a claimed boundary, the '
        'run is skipped on every call: mark the boundary with '
        'dml_utils_lib.mark_migration_run_cancelled(run_id) (granted to the caller '
        'role) and retry. A '
        'non-terminal archived run is left in place; archive_migration_run cancels '
        'unfinished runs, so that state is normally unreachable.';
