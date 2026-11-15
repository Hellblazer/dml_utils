# ADR 0003: Run pause/resume, rows-affected reporting and transient retries

- **Status:** Accepted
- **Date:** 2026-11-11
- **Beads:** `dml-99x` (epic), `dml-99x.1` (this design)

## Context

`run_migration_chunks` currently has three terminal outcomes: it completes,
raises on a chunk failure, or is cancelled. `cancel_migration_run` is
deliberately terminal: it marks the run and its remaining boundaries cancelled,
and the only way to continue is to archive the run and start a new label. There
is no way to stop a run temporarily, let in-flight chunks commit, and resume the
same boundaries later.

The coordinator also discards the result of each chunk statement. It knows how
many chunks completed, but not how many rows each chunk changed, so an empty
chunk is indistinguishable from a productive one and there is no row-based
progress or verification.

Finally, any worker error aborts the run. A transient conflict such as a
deadlock, a serialization failure or a lock-not-available error is safe to
retry: the worker transaction aborted, the boundary claim rolled back, and the
row range is still pending. Requiring the operator to notice and resume the run
turns a transient event into manual work.

This ADR records the semantics for pause/resume, rows-affected reporting and
transient retries. It also records the pre-deployment baseline policy: nothing
is deployed, so the existing changesets and routine files are edited in place
rather than extended with compatibility changesets.

## Decision

### 1. Pause is a durable, non-terminal flag

Add `dml_utils_data.migration_run.paused_at timestamptz`.

A run is **paused** when `paused_at IS NOT NULL`, `completed_at IS NULL` and
`cancelled_at IS NULL`. It remains unfinished and resumable. `archived_at` stays
NULL until the caller archives it.

The table constraint is:

```sql
paused_at IS NULL
    OR (started_at IS NOT NULL
        AND paused_at >= started_at
        AND completed_at IS NULL
        AND cancelled_at IS NULL
        AND archived_at IS NULL)
```

`cancel_migration_run` and `complete_migration_run` clear `paused_at` when they
set their terminal timestamp. This keeps the invariant that a paused run is
never also completed or cancelled. Cancellation wins over pause.

`archived_at` is part of the constraint as well, so a direct INSERT cannot create
a paused-and-archived row: that row would be terminal (the pause could never be
cleared) and the archive delete helper could never remove it, because it needs
`completed_at` or `cancelled_at`. An archived run is therefore never paused.

`dml_utils_data.reject_migration_run_update` allows `paused_at` to be set or
cleared on an unfinished, non-cancelled run. It rejects pausing a terminal run,
rejects a paused run that is also archived (a backstop for the constraint), and
rejects rewriting a non-NULL `paused_at` to another non-NULL timestamp; a
second pause is a no-op. `cancelled_at`, `completed_at` and `archived_at` are
immutable once set, so a finished or archived run cannot be re-opened.

### 2. Pause/resume control functions

```sql
dml_utils.pause_migration_run(i_label dml_utils_data.non_null_text)
    RETURNS bigint

dml_utils.resume_migration_run(i_label dml_utils_data.non_null_text)
    RETURNS bigint
```

Both are `SECURITY DEFINER` with a pinned `search_path`, following
`cancel_migration_run` and `archive_migration_run`.

- `pause_migration_run` sets `paused_at = clock_timestamp()` on the active (`archived_at IS NULL`), unfinished
  (`completed_at IS NULL`), non-cancelled (`cancelled_at IS NULL`) run for the label. If the run is already paused it
  returns the same `run_id` without moving the timestamp. It does not mark
  boundaries or leaves. It returns NULL when there is no active unfinished run.
- `resume_migration_run` clears `paused_at` on the active unfinished,
  non-cancelled run. Resuming a run that is not paused is a no-op that returns
  the run id. It returns NULL when there is no active unfinished run.

Pause is only a request: the coordinator observes it at its normal polling
cadence (at most every ~100ms, like cancellation) and may have chunks in flight.

### 3. Coordinator behaviour while paused

`run_migration_chunks` polls `paused_at` alongside `cancelled_at`.

When a pause is observed during chunk processing:

- it stops launching new chunks;
- it waits for the in-flight workers to finish and commit their current chunk,
  without cancelling them;
- it does not mark pending boundaries cancelled;
- it returns with a notice, leaving the run unfinished and resumable.

If every chunk is already complete when the pause is observed (for example the
pause raced with the last chunk), completion wins: the coordinator records
completion and returns. A paused run with all chunks complete is therefore not
left permanently paused.

Calling `run_migration_chunks` again while the run is paused raises a notice and
returns without processing. It must not auto-resume. The caller resumes with
`resume_migration_run` and then calls `run_migration_chunks` again.

Partition-wise boundary calculation observes the same flag before launching each
leaf worker:

- it stops launching new leaves;
- leaves already calculated keep their boundaries;
- remaining leaves stay `pending`;
- `boundaries_calculated_at` stays NULL;
- it returns without finalizing.

A resume continues at the first `pending` or `failed` leaf, exactly like the
existing calculation-resume path. If a pause arrives after every leaf is
calculated, finalization finishes and the pause then applies to processing.

Cancellation wins at every point. A cancelled run is terminal, takes the
existing cancellation path, and is not resumed.

### 4. Rows affected are recorded per boundary and totalled per run

Add:

- `dml_utils_data.migration_boundary.rows_affected bigint`, NULL until the chunk
  completes (a completed chunk that changed no rows records `0`);
- `dml_utils_data.migration_run.total_rows_affected bigint NOT NULL DEFAULT 0`.

The boundary invariant is:

```sql
(completed_at IS NULL) = (rows_affected IS NULL)
AND (rows_affected IS NULL OR rows_affected >= 0)
```

A pending or cancelled boundary therefore has NULL; a completed boundary has a
non-negative count. The boundary trigger rejects setting `rows_affected` on a
pending boundary and rejects changing it after `completed_at` is set.

`dml_utils_lib.process_migration_chunk` captures the count after `EXECUTE`:

```sql
GET DIAGNOSTICS l_rows_affected = ROW_COUNT;
```

and passes it to `dml_utils_lib.complete_migration_boundary`, which writes
`rows_affected` and advances `total_chunks_completed` and
`total_rows_affected` in the same transaction. A failed chunk rolls the whole
transaction back, so a retry counts once. A recovered retry leaves no partial
count.

For the `run_function_over_table` template (`SELECT fn(...) FROM ...`),
`ROW_COUNT` is the number of rows returned by the SELECT, i.e. the number of rows
passed to the function. For an `UPDATE`, `DELETE` or `INSERT ... SELECT` it is
the number of affected rows. The chunk template is assumed to be one statement;
a multi-statement template counts the last statement.

### 5. Transient retry policy is stored per run

Add:

- `dml_utils_data.migration_run.max_retries dml_utils_data.non_negative_integer
  NOT NULL DEFAULT 3` — retries after the first attempt; `0` disables retries;
- `dml_utils_data.migration_run.retry_backoff_ms
  dml_utils_data.non_negative_integer NOT NULL DEFAULT 1000` — fixed delay
  between attempts; `0` retries immediately.

A new run stores the caller's values. Add trailing initial-value parameters to
the runners, matching `threads` and `sleep_ms`:

```sql
run_migration_chunks(..., i_max_retries, i_retry_backoff_ms)
run_function_over_table(..., i_max_retries, i_retry_backoff_ms)
```

The values flow through `resolve_migration_run` → `create_migration_run` →
`populate_migration_boundaries` and are stored when the run is created. A
resumed run uses the stored values and ignores a differing input with a notice,
like the other stored inputs. The setter changes the stored values for the next
call.

```sql
dml_utils.set_migration_run_retries(
    i_label dml_utils_data.non_null_text,
    i_max_retries dml_utils_data.non_negative_integer,
    i_retry_backoff_ms dml_utils_data.non_negative_integer)
    RETURNS void
```

It updates the unfinished run for the label and raises `P0002` when there is
none. A coordinator already processing keeps the values it read at its start, so
the setter changes the next call.

Only these SQLSTATEs are retryable:

| SQLSTATE | Meaning                 |
|----------|-------------------------|
| `40001`  | `serialization_failure` |
| `40P01`  | `deadlock_detected`     |
| `55P03`  | `lock_not_available`    |

`57014` (`query_canceled`) is not retried: cancellation is an intentional
operator action, not a transient conflict. Arbitrary user SQLSTATEs are not
retried.

The coordinator tracks attempts per boundary in memory. On a retryable worker
error:

1. If attempts remain, wait `retry_backoff_ms` (cancellation- and pause-aware)
   and re-launch the same boundary.
2. If attempts are exhausted, record the failure through
   `record_migration_worker_failure` and take the existing abort/drain path.

A successful retry leaves no `migration_error` row. The existing benign `P0C01`
handling (another coordinator won the claim and the boundary is already
completed or cancelled) is preserved and is not counted as a retry. If
cancellation arrives during the backoff, the cancellation path runs. If pause
arrives, the coordinator stops retrying, leaves the boundary pending and returns.

### 6. Public API surface

Run controls and run introspection:

```sql
dml_utils.run_migration_chunks(..., i_boundary_key_index, i_partition_wise,
                               i_max_retries, i_retry_backoff_ms)
dml_utils.run_function_over_table(..., i_boundary_key_index, i_partition_wise,
                                  i_max_retries, i_retry_backoff_ms)
dml_utils_lib.resolve_migration_run(..., i_max_retries, i_retry_backoff_ms)
dml_utils_lib.populate_migration_boundaries(..., i_max_retries, i_retry_backoff_ms)

dml_utils.pause_migration_run(i_label) RETURNS bigint
dml_utils.resume_migration_run(i_label) RETURNS bigint
dml_utils.set_migration_run_retries(i_label, i_max_retries, i_retry_backoff_ms) RETURNS void
```

Run introspection:

```sql
dml_utils.migration_run_leaves(i_run_id bigint) RETURNS TABLE (...)
dml_utils.migration_run_progress(i_run_id bigint) RETURNS TABLE (...)
```

`migration_run_summary` gains `partition_wise`, `total_chunks`,
`total_chunks_completed`, and leaf counts (`leaf_count`, `leaves_calculated`,
`leaves_failed`, `leaves_pending`). `migration_run_progress` owns the
rows-affected, pause and retry-policy fields so the summary stays a per-label run
index; it reports `total_rows_affected`, `paused_at`, `max_retries` and
`retry_backoff_ms` alongside its derived progress values.

`migration_boundaries` gains `leaf_ordinal`, `rows_affected`, `cancelled_at` and
`duration_ms` (NULL while unfinished). It remains ordered by `boundary_no`.

`migration_run_progress` reports a derived `status`
(`archived`/`cancelled`/`completed`/`paused`/`calculating`/`active`),
`percent_complete`, elapsed time, chunk and row rates, and an estimated
completion time. Rates and ETA are NULL when there is not enough data; an empty
completed run reports 100%.

Partition-wise explain:

```sql
dml_utils.explain_migration_chunks(..., i_partition_wise, i_leaf_ordinal)
dml_utils.explain_migration_chunk(..., i_partition_wise, i_leaf_ordinal)
dml_utils.explain_migration_run_chunk(i_run_id, i_boundary_no)
```

`i_leaf_ordinal` is the deterministic manifest ordinal, starting at 1; NULL
selects the first leaf. Name-based synthetic selection is deferred; a caller that
has a run can map a leaf name to its ordinal with `migration_run_leaves`. The
public explain functions return the resolved leaf schema/name/ordinal. A leaf
selector on a non-partition-wise call is rejected.

`explain_migration_run_chunk` resolves the boundary's stored leaf, uses the
manifest high-water snapshot for the last chunk of a leaf, renders the same SQL
the runner would execute, and fails closed if the leaf/manifest no longer
matches. Terminal and missing boundaries are rejected.

Function-over-table:

```sql
dml_utils.run_function_over_table(..., i_boundary_key_index, i_partition_wise)
dml_utils.explain_function_over_table(..., i_boundary_key_index, i_partition_wise, i_leaf_ordinal)
dml_utils.explain_function_over_table_chunk(..., i_chunk_by, i_boundary_key_index,
                                            i_partition_wise, i_leaf_ordinal)
```

The function still receives the driving table's primary-key column types in key
order. The chunk key is independent: it can be the primary key or a named unique
index, and it can be applied leaf by leaf. The derived label includes the new
arguments so calls that differ only in the key or partition flag derive different
runs.

### 7. Schema and baseline policy

Nothing is deployed, so this epic edits the baseline in place:

- edit `changes/sql_changes/006-create-migration-tables.sql`, its rollback, the
  trigger function files, routine files and `999-grant-privileges.sql` directly;
- do not add compatibility or backfill changesets and do not add `DROP FUNCTION`
  statements for old signatures — a fresh database defines only the new
  signature;
- brand-new routines still use the normal one-changeset-per-routine convention
  in `changes/functions.xml`;
- `LiquibaseUpgradeTest` was disabled for the baseline rewrite and was restored
  in `dml-99x.3` against `v1.19.0`, the first release cut from the cleaned
  changelog;
- recreate local/dev/benchmark databases after the edits.

## Alternatives considered

- **A separate `status` enum column.** Rejected: the run already has
  `started_at`, `boundaries_calculated_at`, `completed_at`, `cancelled_at` and
  `archived_at`; a nullable `paused_at` fits that model and avoids a second
  source of truth.
- **Pause as cancel plus archive.** Rejected: that discards progress and forces
  a new label. Pause must preserve the boundaries.
- **Auto-resume when `run_migration_chunks` is called on a paused run.**
  Rejected: it makes the pause flag meaningless and lets an unrelated re-call
  start work the operator deliberately stopped.
- **A transaction-scoped advisory lock for pause.** Rejected: pause is durable
  state, not a lock held by the current session; the flag must survive a
  coordinator restart.
- **Store only per-boundary rows and aggregate the run total on read.**
  Rejected for the hot path: the run total is cheap to advance in the same
  transaction as completion and gives a constant-time progress query. The
  per-boundary values remain the audit detail.
- **Retry every error.** Rejected: a data error, a constraint violation or a
  user SQLSTATE is not transient and retrying hides the problem.
- **Retry inside the worker transaction.** Rejected: the worker transaction has
  aborted; a retry is a new transaction and belongs to the coordinator.
- **Infinite retries or an unbounded backoff.** Rejected: a persistent conflict
  must eventually surface to the operator.
- **Retry `57014`.** Rejected: query cancellation is an operator action; the
  coordinator must stop.
- **Name-based synthetic leaf selection in v1.** Deferred: ordinal selection is
  deterministic, and `migration_run_leaves` exposes names for a real run.
- **New compatibility changesets.** Rejected: nothing has been deployed, so
  there is no installed baseline to migrate.

## Consequences

Positive:

- A run can be paused and resumed without losing boundaries or committed chunks.
- Empty and productive chunks are distinguishable, and progress can be reported
  in rows as well as chunks.
- Transient deadlocks and serialization conflicts self-heal within a bounded
  policy instead of requiring manual intervention.
- The public API exposes the partition leaf manifest, real-chunk explain and
  partition-wise function runs.

Negative / risks:

- `migration_run` and `migration_boundary` gain columns and trigger rules.
- The coordinator loop becomes more complex: pause polling, retry attempts and
  backoff, and the interaction between them.
- A retry still consumes a worker slot and delays the next chunk; it is not
  free.
- The pause/complete/cancel race needs explicit tests.
- The baseline rewrite required suspending the upgrade test until `v1.19.0` was
  released; `dml-99x.3` restored it.

## Non-goals

- Per-chunk `lock_timeout`, `statement_timeout` or `work_mem` settings.
- Cross-run worker budgets, continue-on-error/quarantine and a preflight
  validator.
- New key types, keys with more than three columns, hash/bucket chunking,
  expression/partial/`DESC` ordered keys and non-unique-plus-tiebreaker indexes.
- Parallel partition boundary calculation.
- Returning a run id/result from the runner, listing/retention helpers and an
  event stream.
- Name-based synthetic leaf selection.

## Implementation map

| Bead                      | Work                                                          |
|---------------------------|---------------------------------------------------------------|
| `dml-99x.1`               | This design                                                   |
| `dml-99x.2`               | Suspend the upgrade test for the baseline edit                |
| `dml-99x.4`–`dml-99x.8`   | Partition-wise function, explain, leaf manifest and benchmark |
| `dml-99x.9`–`dml-99x.11`  | Pause schema, control functions and runner                    |
| `dml-99x.12`–`dml-99x.14` | Rows-affected schema/capture and progress                     |
| `dml-99x.15`–`dml-99x.17` | Retry policy schema, setter and runner                        |
| `dml-99x.18`–`dml-99x.19` | Documentation and full verification                           |
| `dml-99x.3`               | Restore the upgrade test after the next release               |

All implementation beads through `dml-99x.19` are merged and `v1.19.0` was cut
from the cleaned changelog. `dml-99x.3` restores the upgrade test against that
release, completing the epic.
