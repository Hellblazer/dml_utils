# Stored functions

One function per `.sql` file, grouped by the schema that owns it:

- `dml_utils/` — the caller-facing API (`run_migration_chunks`,
  `run_function_over_table`, the run controls, the explain helpers and the
  inspection/cleanup helpers).
- `dml_utils_lib/` — the engine: the generic catalog and template helpers, and
  the internal routines that populate boundaries, run one chunk and record
  errors.
- `dml_utils_data/` — the data layer: the trigger functions that guard the
  migration tables.

Dependencies point downward: `dml_utils_lib` may reference `dml_utils_data`;
`dml_utils` may reference both; nothing references `dml_utils`.

`changes/functions.xml` loads them one `createProcedure` per `runOnChange`
changeset; the matching drop lives in `changes/functions-rollback/`. Every
routine is `SECURITY INVOKER` unless it genuinely needs `SECURITY DEFINER`.

The chunk executor is deliberately `SECURITY INVOKER`: `process_migration_chunk`
runs caller-supplied SQL with the caller's privileges. Metadata writes are
delegated to owner-owned `SECURITY DEFINER` helpers (`claim_migration_boundary`,
`complete_migration_boundary`, `complete_migration_run`,
`mark_migration_run_cancelled`, and `record_migration_error`), so the worker does
not need direct DML on `dml_utils_data`. The caller role is granted only
`EXECUTE`/`USAGE` plus `SELECT` on the migration metadata (see
`sql_changes/999-grant-privileges.sql`); application-table privileges belong to
the application/login role.

This is not a hard authorization boundary: the metadata helpers are callable by
the caller because the invoker-owned worker runs as that same role. It centralizes
metadata writes and removes direct DML, but a malicious template can still call
the helpers. Treat raw templates as trusted, or add a parser/template allowlist.

One exception: `dml_utils_lib.migration_key_is_canonical` is loaded earlier, by
`changes/sql_changes/005-create-migration-key-check.sql`, because the
`migration_boundary_key_check` constraint is created with its table and calls it.
Its drop lives in `changes/rollback/`.

Each routine file ends with a `COMMENT ON FUNCTION` (or `COMMENT ON PROCEDURE`)
for the routine it creates, using the short form (`schema.name`, no argument
list). If a routine is ever overloaded, include its argument types so the
comment targets the right overload.

## Chunking strategies

A run chunks the driving table one of two ways, recorded in the run's `chunk_by`
(`dml_utils_data.chunking_strategy`):

- **`primary_key`** (the default): fixed-row chunks from the primary-key order.
  The boundaries are primary-key values, so they are stable and a run is
  resumable.
- **`blocks`**: fixed-block chunks from the physical heap order. The boundaries
  are heap block numbers and a chunk is the half-open ctid range
  `ctid >= '(start,0)' AND ctid < '(end,0)'`, which plans as a TID Range Scan (PostgreSQL 14+). Boundary computation is
  O (1) from `pg_relation_size`, with no
  table scan, and the chunks are physically sequential.

Prefer `primary_key` unless block chunking is specifically wanted. The block
strategy is only valid for a **quiescent, read-only driving table on a plain
heap**, and it has hard limitations:

- **ctid is not stable.** A non-HOT `UPDATE` changes a tuple's ctid and may move
  it to another block; HOT updates keep it on the page but cannot be relied on.
  A block run assumes the table is not modified while it runs.
- **Heap rewrites change every ctid.** `VACUUM FULL`, `CLUSTER`, `pg_repack`,
  `TRUNCATE`, a tablespace move and rewrite-causing `ALTER TABLE` all rewrite the
  heap. A block run records the driving table's `pg_relation_filepath` and fails
  closed if it changed before a resume, but a rewrite during a run is not
  detected.
- **Inserts land wherever there is free space**, often mid-file, so a block range
  is not a stable partition of the rows.
- **Block chunks have uneven row counts** (fillfactor and dead space), so equal
  blocks is not equal rows.
- **A run is not resumable across a heap rewrite**, and ctid is not preserved by
  a logical `pg_dump`/restore.
- **Plain heap tables only.** ctid is per-partition, so partitioned and foreign
  tables cannot use the block strategy.
- **Read-only cannot be proven.** The routines execute caller-supplied SQL with
  the caller's privileges and cannot tell whether it (or another session)
  modifies the driving table, so a quiescent, read-only source is a caller
  contract, not an enforced property.

### Workaround for idempotent transformations

If the transformation is idempotent and every relevant write fires a trigger, a
`BEFORE UPDATE` trigger on the table can apply the same transformation alongside
the chunked bulk pass: a row that moves is then transformed by the trigger
regardless of which chunk it lands in, and applying it twice is harmless. This is
an advanced mitigation, not a guarantee. The transformation must be idempotent,
the trigger must not recurse, and it does not make deletes, concurrent DML or
heap rewrites safe; it does not replace a quiescent source.

## `dml_utils`

###

`dml_utils.run_migration_chunks(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label, i_chunk_size [, i_threads, i_driving_table_alias, i_chunk_by, i_poll_timeout_ms, i_sleep_ms])`

```sql
i_sql_text                  dml_utils_data.non_null_text
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_label                     dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer DEFAULT 1000
i_threads                   dml_utils_data.positive_integer DEFAULT 1
i_driving_table_alias       dml_utils_data.non_null_text DEFAULT 't'
i_chunk_by                  dml_utils_data.chunking_strategy DEFAULT 'primary_key'
i_poll_timeout_ms           dml_utils_data.positive_integer DEFAULT 1
i_sleep_ms                  dml_utils_data.non_negative_integer DEFAULT 0
RETURNS void
```

`VOLATILE`, `SECURITY INVOKER`. Processes every chunk of the driving table, up to
`i_threads` `pg_background` workers at a time (default 1). `i_chunk_by` selects
the strategy: `primary_key` (the default) chunks fixed rows in primary-key order;
`blocks` chunks physical heap block ranges and requires a quiescent, read-only
plain table (see "Chunking strategies" above). Each
worker runs one chunk in its own transaction; the coordinator only schedules
and holds no locks on the driving table. On a failed chunk the coordinator
records it, stops launching new chunks, lets the workers already in flight
commit their current chunk, and then re-raises the error, leaving the run
incomplete so it can be resumed. `i_threads` must not exceed
`max_worker_processes` (`22023` otherwise), and parallel execution is not
always faster: the chunks never overlap, but they still contend for the same
table's locks and pages. `i_sleep_ms` adds a delay after each successfully
completed chunk (except the final one), which throttles WAL generation and
therefore helps when replication lag is the constraint. A NULL or negative
value is rejected by the `non_negative_integer` domain with a domain
violation (`23514`); there is no arbitrary upper bound, so an operator can intentionally choose a very long
delay (cancellation remains responsive). It is stored on the run
like `i_threads`; a resumed run uses the stored value, and
`set_migration_run_sleep_ms` adjusts it between calls. With several threads, up
to that many chunks can still be in flight, so use a small thread count and/or
smaller chunks for strict pacing. `i_sql_text` is a template with
`<driving_table>` and `<chunking_clause>` (see `render_chunk_sql`). Reuses the
active run for the label, or creates it by running
`dml_utils_lib.populate_migration_boundaries` in a worker so its boundaries
commit before the chunks run (run resolution is delegated to
`dml_utils_lib.resolve_migration_run`). A resumed run uses the recorded SQL
text, chunk size, strategy, threads, sleep and driving table; a differing
input is ignored with a notice, so the boundaries and the rendered chunk SQL
always refer to the same table. Use `set_migration_run_sql_text`,
`set_migration_run_threads` or `set_migration_run_sleep_ms` to change the
recorded SQL text, thread count or per-chunk delay of an unfinished run. Each chunk
worker claims its boundary and commits autonomously, so a re-run resumes at the
first unclaimed boundary. The run records three actual-server-time (`clock_timestamp()`) milestones: `started_at` when
the run begins (the boundary
calculation starts) and `boundaries_calculated_at` when the range calculation
finishes, both written by `populate_migration_boundaries` in its worker's
transaction so they commit with the run and persist across a failed processing
attempt; and `completed_at` when the chunks finish, written in the caller's
transaction so a failed attempt rolls it back. Raises `unique_violation` (`23505`)
when another active run already exists for the label, and re-raises a chunk
worker's failure
with its original SQLSTATE. `RAISE NOTICE` and returns when the run is already
complete. A run cancelled with `cancel_migration_run` stops launching, cancels
the in-flight workers, marks the run and its remaining boundaries cancelled and
returns; a cancelled run is terminal. Run under `READ COMMITTED`; do not hold locks on the driving table
across the call. `i_poll_timeout_ms` is how long the coordinator waits for a
worker before re-checking (`pg_background_wait_any`): the default `1` keeps
per-chunk detection latency low, and a larger value polls less often, cutting
coordinator CPU for chunks that run in non-trivial time. It must be positive:
`0` is rejected because `pg_background_wait_any` treats a non-positive timeout
as wait-forever.

###

`dml_utils.explain_migration_chunks(i_sql_text, i_driving_table_schema_name, i_driving_table_name [, i_chunk_size, i_driving_table_alias, i_chunk_by])`

```sql
i_sql_text                  dml_utils_data.non_null_text
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer DEFAULT 1000
i_driving_table_alias       dml_utils_data.non_null_text DEFAULT 't'
i_chunk_by                  dml_utils_data.chunking_strategy DEFAULT 'primary_key'
RETURNS TABLE
    (o_plan_kind text, o_sql_text text, o_plan json)
```

`VOLATILE`, `SECURITY INVOKER`. The read-only companion to
`run_migration_chunks`: it returns `EXPLAIN (FORMAT JSON)` of the SQL a chunked
run would generate, one row per statement, without executing anything and
without writing a run or boundaries. `o_plan_kind` is `boundary_population` (the
range scan and boundary INSERT), `chunk_non_final` (a half-open range) or
`chunk_final`; `o_sql_text` is the exact SQL that was explained. The boundary
INSERT is explained with a synthetic run id (`0`), which plans as a `ModifyTable`
without evaluating the foreign key. For `primary_key` the chunk ranges come from
`dml_utils_lib.synthetic_chunk_boundary_values` (a `<` non-final and a `<=` final
range); for `blocks` they are two representative half-open ctid ranges. Either
way the ranges are synthetic, so their row estimates may differ from a real
chunk. It is `VOLATILE` because PostgreSQL forbids `EXPLAIN` in a non-volatile
function. Use it to inspect the plans before running `run_migration_chunks`.

###

`dml_utils.explain_migration_chunk(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_start_values, i_end_values, i_is_final [, i_driving_table_alias])`

```sql
i_sql_text                  dml_utils_data.non_null_text
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_start_values              text[]
i_end_values                text[]
i_is_final                  boolean
i_driving_table_alias       dml_utils_data.non_null_text DEFAULT 't'
RETURNS TABLE
    (o_plan_kind text, o_sql_text text, o_plan json)
```

`VOLATILE`, `SECURITY INVOKER`. The explicit-boundary companion to
`explain_migration_chunks`: it explains exactly one chunk, whose start and end
boundaries the caller supplies as one text value per primary-key column, in key
order. `o_plan_kind` is `chunk_non_final` for a half-open range or `chunk_final`
for the final, inclusive chunk; `o_sql_text` is the SQL that was explained. Use
it to explain a real chunk (for example a boundary pair read from
`dml_utils.migration_boundaries`) instead of only the synthetic representative
ranges, which is the only way to see the prefix-equality predicate shapes a
multi-column key produces when the leading key columns do not differ between the
boundaries. Only the `primary_key` strategy is explained; a `blocks` run's
boundaries are block numbers that render a ctid range, so passing them here
would render a misleading primary-key range. The values are validated and cast
by their column's kind, and a reversed range, an empty non-final range, or an
array whose length does not match the key is rejected with
`invalid_parameter_value` (`22023`). It resolves
no run, reads no boundaries and writes nothing.

### `dml_utils.set_migration_run_sql_text(i_label, i_sql_text)`

```sql
i_label    dml_utils_data.non_null_text
i_sql_text dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded `sql_text` of the unfinished (`completed_at IS NULL`) run for the label, so
the next `run_migration_chunks`
call uses the adjusted SQL. Validates the new SQL as a chunking template (raising `invalid_parameter_value`, `22023`, if
it is not), and raises
`no_data_found` (`P0002`) when there is no unfinished run for the label. Use
this to adjust the SQL of an existing run (for example to fix a bad execution
plan) instead of passing a changed template to a resumed `run_migration_chunks`
call, which would ignore it.

###

`dml_utils.run_function_over_table(i_driving_table_schema_name, i_driving_table_name, i_function_schema_name, i_function_name [, i_chunk_size, i_threads, i_label, i_filter, i_chunk_by, i_poll_timeout_ms, i_sleep_ms])`

```sql
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_function_schema_name      dml_utils_data.non_null_text
i_function_name             dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer DEFAULT 1000
i_threads                   dml_utils_data.positive_integer DEFAULT 1
i_label                     text DEFAULT NULL
i_filter                    text DEFAULT NULL
i_chunk_by                  dml_utils_data.chunking_strategy DEFAULT 'primary_key'
i_poll_timeout_ms           dml_utils_data.positive_integer DEFAULT 1
i_sleep_ms                  dml_utils_data.non_negative_integer DEFAULT 0
RETURNS void
```

`VOLATILE`, `SECURITY INVOKER`. A wrapper for `run_migration_chunks` that calls
a user-supplied function once per row of the driving table, chunk by chunk.
Instead of a hand-written template, the caller supplies a function whose
arguments are the driving table's primary-key columns, in key order, and which
returns `void`. The wrapper builds the per-row template
(`SELECT <fn>(t.<pk1>, ...) FROM <driving_table> WHERE <chunking_clause>`) and
delegates to `run_migration_chunks`, so it resumes by label exactly like a base
run. The function's argument types must equal the primary-key column types in key
order and it must return `void`; a missing function, a non-`void` return, or
mismatched argument types raise `invalid_parameter_value` (`22023`) before any
run is created. When `i_label` is NULL a deterministic label is derived from the
driving table, function, filter and chunking strategy, so re-running the same
call resumes the same run and calls that differ only in those derive different
runs. A non-NULL `i_filter` is ANDed onto every chunk's range predicate (referencing the driving table through the fixed
alias `t`), so only the rows it
matches are passed to the function; a filter that contains `<driving_table>` or
`<chunking_clause>` is rejected when the template is validated. `i_chunk_by`
selects the strategy; `blocks` requires a quiescent, read-only driving table and
is unsafe when the function updates the driving table in place. Because a resumed
run uses the recorded SQL text and strategy, use `set_migration_run_function` to
point an unfinished run at a different function or filter. `i_poll_timeout_ms` and
`i_sleep_ms` are passed through to `run_migration_chunks` (see there for their
meaning); `i_sleep_ms` seeds a new run and a resumed run uses the stored value.

###

`dml_utils.explain_function_over_table(i_driving_table_schema_name, i_driving_table_name, i_function_schema_name, i_function_name [, i_chunk_size, i_filter, i_chunk_by])`

```sql
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_function_schema_name      dml_utils_data.non_null_text
i_function_name             dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer DEFAULT 1000
i_filter                    text DEFAULT NULL
i_chunk_by                  dml_utils_data.chunking_strategy DEFAULT 'primary_key'
RETURNS TABLE
    (o_plan_kind text, o_sql_text text, o_plan json)
```

`VOLATILE`, `SECURITY INVOKER`. The read-only companion to
`run_function_over_table`: it validates the function against the driving table's
primary key, builds the per-row template and returns the same three plans as
`explain_migration_chunks` (`boundary_population`, `chunk_non_final`,
`chunk_final`) for the given chunking strategy. A missing function, a non-`void`
return or mismatched argument types raise `invalid_parameter_value` (`22023`)
before any plan is produced. A non-NULL `i_filter` is ANDed onto the chunk plans'
range predicate; the boundary-population plan (which does not use the template)
is unaffected. For `blocks`, the driving table must be a plain heap.

###

`dml_utils.explain_function_over_table_chunk(i_driving_table_schema_name, i_driving_table_name, i_function_schema_name, i_function_name, i_start_values, i_end_values, i_is_final [, i_filter])`

```sql
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_function_schema_name      dml_utils_data.non_null_text
i_function_name             dml_utils_data.non_null_text
i_start_values              text[]
i_end_values                text[]
i_is_final                  boolean
i_filter                    text DEFAULT NULL
RETURNS TABLE
    (o_plan_kind text, o_sql_text text, o_plan json)
```

`VOLATILE`, `SECURITY INVOKER`. The explicit-boundary companion to
`explain_function_over_table`: it validates the function against the driving
table's primary key, builds the per-row template and explains exactly one chunk
whose start and end boundaries the caller supplies as one text value per
primary-key column, in key order. Only the `primary_key` strategy is explained:
a `blocks`-strategy chunk is a half-open ctid range and cannot be expressed
here. It returns one `chunk_non_final` or `chunk_final` row. A non-NULL
`i_filter` is ANDed onto the chunk predicate. Use
it to explain a real chunk of the per-row call, including the prefix-equality
shapes the synthetic ranges never produce. It resolves no run, reads no
boundaries and writes nothing.

### `dml_utils.set_migration_run_function(i_label, i_function_schema_name, i_function_name [, i_filter])`

```sql
i_label               dml_utils_data.non_null_text
i_function_schema_name dml_utils_data.non_null_text
i_function_name       dml_utils_data.non_null_text
i_filter              text DEFAULT NULL
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded `sql_text` of the unfinished run for
the label with the template that calls the given function over the run's driving
table (which is immutable), so the next `run_function_over_table` call uses the
adjusted function. Validates the function against the driving table's primary
key (raising `invalid_parameter_value`, `22023`, on a mismatch) and raises
`no_data_found` (`P0002`) when there is no unfinished run for the label. A
non-NULL `i_filter` is ANDed onto each chunk's range predicate; a NULL `i_filter`
removes any filter from the stored template.

### `dml_utils.set_migration_run_threads(i_label, i_threads)`

```sql
i_label   dml_utils_data.non_null_text
i_threads dml_utils_data.positive_integer
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded `threads` of the unfinished (`completed_at IS NULL`) run for the label, so the
next `run_migration_chunks`
call uses the adjusted worker count. Raises `no_data_found` (`P0002`) when there
is no unfinished run for the label. Use this to tune the parallelism of an
existing run instead of passing a changed `i_threads` to a resumed call, which
would ignore it.

### `dml_utils.set_migration_run_sleep_ms(i_label, i_sleep_ms)`

```sql
i_label    dml_utils_data.non_null_text
i_sleep_ms dml_utils_data.non_negative_integer
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded per-chunk sleep of the unfinished (`completed_at IS NULL`) run for the label,
so the next `run_migration_chunks`
call waits that many milliseconds after each successful chunk before launching
another. Raises a domain violation (`23514`) for a NULL or negative value and
`no_data_found` (`P0002`) when there is no unfinished run for the label. A
coordinator already processing the run keeps the value it read at its start, so
this changes the next call (a restart/resume), not a run in progress.

### `dml_utils.archive_migration_run(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS bigint
```

`SECURITY INVOKER`. Sets `archived_at` on the active (not archived) run for the
label, if any, so the label can be reused; returns the archived `run_id`, or
NULL when the label had no active run. Pair it with
`dml_utils_lib.populate_migration_boundaries` to rerun a label: populate,
archive, populate again.

### `dml_utils.cancel_migration_run(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS bigint
```

`SECURITY INVOKER`. Cancels the active (not archived, unfinished) migration run
for the label, if any: sets its `cancelled_at`, marks the unclaimed pending
boundaries `cancelled_at`, and returns the `run_id`, or NULL when there was no
active unfinished run. Boundaries held by an in-flight worker are left for
`run_migration_chunks` to mark after it cancels that worker. A cancelled run is
terminal: archive it with `archive_migration_run` and create a new run to
resume.

### `dml_utils.delete_archived_migration_runs()` and `dml_utils.delete_archived_migration_runs(i_label)`

```sql
-- delete_archived_migration_runs(): no arguments
-- delete_archived_migration_runs(i_label):
i_label dml_utils_data.non_null_text
RETURNS bigint
```

`SECURITY INVOKER`. Deletes archived runs — every one, or only those for the
label — and returns the number deleted. Deleting a run cascades to its
`migration_boundary` and `migration_error` rows. The overloads share a
changeset (`function-dml_utils.delete_archived_migration_runs`).

### `dml_utils.migration_run_summary(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS TABLE
( run_id, label, chunk_size, chunk_by, threads, sleep_ms,
    driving_table_schema_name, driving_table_name, driving_table_relation_filepath,
    created_at, started_at, boundaries_calculated_at, completed_at, cancelled_at, archived_at,
    boundary_count, completed_boundary_count, error_count)
```

`STABLE`, `SECURITY INVOKER`. Returns one high-level row per run for the label,
including the chunking strategy (`chunk_by`), the thread count, the per-chunk
sleep, the block run's physical filepath (`driving_table_relation_filepath`), the
number of boundaries (chunk starts plus
the terminal boundary), how many are completed, and the number of recorded
errors. Use `migration_errors` to list the errors themselves.

### `dml_utils.migration_errors(i_run_id)`

```sql
i_run_id bigint
RETURNS TABLE
    (error_id, boundary_no, sqlstate, message, created_at)
```

`STABLE`, `SECURITY INVOKER`. Returns the run's recorded errors, ordered by
`error_id`.

### `dml_utils.migration_boundaries(i_run_id)`

```sql
i_run_id bigint
RETURNS TABLE
    (boundary_no, boundary_id, started_at, completed_at)
```

`STABLE`, `SECURITY INVOKER`. Returns the run's chunk boundaries in order.
`boundary_id` is a `dml_utils_data.migration_key`: position-aligned arrays, one
per key kind. Index i is the i-th primary-key column, so a bigint key's first
column is `(boundary_id).bigint_values[1]`. A `started_at` of `NULL` means the
chunk has not been claimed; a `completed_at` of `NULL` (with `started_at` set)
means it is in progress.

## `dml_utils_lib`

`dml_utils_lib` is the engine. It may reference `dml_utils_data`, but never
`dml_utils`.

### `dml_utils_lib.assert_schema_exists(i_schema_name)`

```sql
i_schema_name dml_utils_data.non_null_text
RETURNS void
```

`STABLE`, `SECURITY INVOKER`. Raises `invalid_schema_name` (`3F000`) when the
schema does not exist.

### `dml_utils_lib.assert_table_exists(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS void
```

`STABLE`, `SECURITY INVOKER`. Raises `undefined_table` (`42P01`) when the table
does not exist in the schema.

### `dml_utils_lib.primary_key_attributes(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS TABLE
(ordinality integer, column_name name, column_oid oid,
    column_type regtype, key_kind text)
```

`STABLE`, `SECURITY INVOKER`. The single catalog reader for a table's primary
key: `primary_key_columns` and `primary_key_kinds` delegate to it, so the key
order and the one-to-three-column validation live in one place. Each row is one
key column in key order, with its ordinal position, name, type oid, type and
collapsed boundary kind (`bigint`/`text`/`uuid`, or NULL for an unsupported
type). Raises `invalid_parameter_value` (`22023`) when the table has no primary
key or more than 3 key columns.

### `dml_utils_lib.primary_key_columns(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS name[]
```

`STABLE`, `SECURITY INVOKER`. Returns the table's primary-key columns in key
order (from the index, so a key declared `(b, a)` returns `{b, a}`), raising
`invalid_parameter_value` (`22023`) when the table has no primary key or more
than 3 key columns. Delegates the column read to `primary_key_attributes`.

### `dml_utils_lib.primary_key_kinds(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS text[]
```

`STABLE`, `SECURITY INVOKER`. Returns the boundary key kind of each primary-key
column, in key order: `bigint` for `smallint`/`integer`/`bigint`, `text` for a
`text` key, and `uuid` for a `uuid` key. Raises `invalid_parameter_value`
(`22023`) for any other type.

### `dml_utils_lib.ordered_key_attributes(i_schema_name, i_table_name, i_index_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_index_name  dml_utils_data.non_null_text
RETURNS TABLE
(ordinality integer, column_name name, column_oid oid,
    column_type regtype, key_kind text)
```

`STABLE`, `SECURITY INVOKER`. The single catalog reader and validator for an
ordered key: resolves the named unique btree index on the driving table to one
row per key column in index order, with its ordinal position, name, type oid,
type and collapsed boundary kind (`bigint`, `text`, `uuid` or `timestamptz`).
`INCLUDE` columns are ignored. It rejects:

| Situation | SQLSTATE |
|---|---|
| Missing index on the table | `42704` |
| Missing schema / table | `3F000` / `42P01` |
| Non-plain (partitioned) table, non-btree, non-unique, partial, expression, DESC / NULLS FIRST, non-default opclass, collation mismatch, nullable key column | `0A000` |
| Index not valid/ready/live | `55000` |
| Wrong arity (more than three key columns) or unsupported key type | `22023` |

The index must be a valid, live, unique btree index on one to three simple
`NOT NULL` columns using the type's default btree operator class and the
column's own collation. An exclusion-constraint index is rejected by the
non-btree check, because PostgreSQL backs exclusion constraints only with
gist/spgist.

### `dml_utils_lib.ordered_key_columns(i_schema_name, i_table_name, i_index_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_index_name  dml_utils_data.non_null_text
RETURNS name[]
```

`STABLE`, `SECURITY INVOKER`. Returns the ordered-key columns in index order,
delegating validation to `ordered_key_attributes`.

### `dml_utils_lib.ordered_key_kinds(i_schema_name, i_table_name, i_index_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_index_name  dml_utils_data.non_null_text
RETURNS text[]
```

`STABLE`, `SECURITY INVOKER`. Returns the collapsed boundary key kind of each
ordered-key column in index order, delegating validation to
`ordered_key_attributes`.

### `dml_utils_lib.assert_usable_ordered_key(i_schema_name, i_table_name, i_index_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_index_name  dml_utils_data.non_null_text
RETURNS void
```

`STABLE`, `SECURITY INVOKER`. Raises the `ordered_key_attributes` error when the
index cannot serve as an ordered key, and returns normally when it can. Used by
the run path and by resume validation. A caller that also needs the descriptor
should read `ordered_key_attributes` once instead of calling this wrapper and
then `ordered_key_columns`/`ordered_key_kinds`, which would repeat the catalog
read and validation.

### `dml_utils_lib.migration_key_is_canonical(i_key)`

```sql
i_key dml_utils_data.migration_key
RETURNS boolean
```

`IMMUTABLE`, `SECURITY INVOKER`. True when the key is a canonical boundary key:
its arity is one to three, every present array shares that arity, exactly one
array holds a non-NULL element at each index, and no present array is empty or
all-NULL. The `migration_boundary_key_check` constraint calls it.

### `dml_utils_lib.migration_key_values(i_key, i_key_kinds)`

```sql
i_key       dml_utils_data.migration_key
i_key_kinds text[]
RETURNS text[]
```

`IMMUTABLE`, `SECURITY INVOKER`. Flattens a position-aligned `migration_key`
into one text value per primary-key column, in key order, using `i_key_kinds` to
pick the array for each position. The chunk predicate re-casts each value. Pure
casts and array element access, so it is `IMMUTABLE`.

### `dml_utils_lib.migration_key_pair_values(i_start_key, i_end_key, i_key_kinds)`

```sql
i_start_key dml_utils_data.migration_key
i_end_key   dml_utils_data.migration_key
i_key_kinds text[]
RETURNS record (o_start_values text[], o_end_values text[])
```

`IMMUTABLE`, `SECURITY INVOKER`. Flattens a chunk's two boundaries (its start
and end `migration_key`) in one pass, returning one text value per primary-key
column for each, in key order. It is the two-key form of
`migration_key_values`, so the coordinator extracts both bounds with a single
call per chunk instead of two. Rejects malformed kinds or an unsupported kind
with `22023`.

###

`dml_utils_lib.build_function_chunk_template(i_table_schema_name, i_table_name, i_function_schema_name, i_function_name [, i_filter])`

```sql
i_table_schema_name    dml_utils_data.non_null_text
i_table_name           dml_utils_data.non_null_text
i_function_schema_name dml_utils_data.non_null_text
i_function_name        dml_utils_data.non_null_text
i_filter               text DEFAULT NULL
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Returns the `<driving_table>`/`<chunking_clause>`
template that calls the given function once per row, passing the driving table's
primary-key columns in key order (`SELECT <fn>(t.<pk1>, ...) FROM <driving_table> WHERE <chunking_clause>`).
Resolves the primary key via `primary_key_columns` and validates that the
function exists and returns `void` with argument types equal to the primary-key
column types in key order; raises `invalid_parameter_value` (`22023`) otherwise. A
non-NULL `i_filter` is appended as ` AND (<i_filter>)`, so only the rows it
matches are passed to the function; it references the driving table through the
fixed alias `t`.

###

`dml_utils_lib.resolve_migration_run(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label, i_chunk_size, i_threads, i_driving_table_alias [, i_chunk_by, i_sleep_ms])`

```sql
i_sql_text                  dml_utils_data.non_null_text
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_label                     dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer
i_threads                   dml_utils_data.positive_integer
i_driving_table_alias       dml_utils_data.non_null_text
i_chunk_by                  dml_utils_data.chunking_strategy DEFAULT 'primary_key'
i_sleep_ms                  dml_utils_data.non_negative_integer DEFAULT 0
RETURNS record (o_run_id bigint, o_already_completed boolean,
                o_effective_sql_text text, o_effective_schema_name text,
                o_effective_table_name text, o_effective_alias text,
                o_effective_threads integer, o_effective_sleep_ms integer,
                o_chunk_by dml_utils_data.chunking_strategy,
                o_primary_key_columns name[], o_key_kinds text[])
```

`SECURITY INVOKER`. The run-resolution half of `run_migration_chunks`: it reuses
the active run for the label or creates one (populating its boundaries in a
`pg_background` worker so they commit autonomously), then returns the run id,
whether it was already complete, the effective sql_text/schema/table/alias/
threads, sleep and chunking strategy (the stored values for a resumed run,
ignoring differing inputs with a notice), and the boundary key columns and kinds (the real
primary key for `primary_key`, or a single `bigint` block number for `blocks`). A
resumed block run fails closed if the driving table's physical filepath changed.
It validates the thread count against `max_worker_processes` before creating a
run.

### `dml_utils_lib.assert_chunking_template(i_sql_text)`

```sql
i_sql_text dml_utils_data.non_null_text
RETURNS void
```

`IMMUTABLE`, `SECURITY INVOKER`. Raises `invalid_parameter_value` (`22023`)
unless `i_sql_text` contains `<driving_table>` and `<chunking_clause>` exactly
once each.

###

`dml_utils_lib.render_chunk_sql(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_primary_key_columns, i_key_kinds, i_start_values, i_end_values, i_is_final)`

```sql
i_sql_text          dml_utils_data.non_null_text
i_schema_name       dml_utils_data.non_null_text
i_table_name        dml_utils_data.non_null_text
i_table_alias       dml_utils_data.non_null_text
i_primary_key_columns name[]
i_key_kinds         text[]
i_start_values      text[]
i_end_values        text[]
i_is_final          boolean
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Validates the template (see
`assert_chunking_template`) and returns it with `<driving_table>` replaced by
`"<schema>"."<table>" "<alias>"` and `<chunking_clause>` replaced by a
parenthesized disjunction of axis-aligned boxes that exactly tiles the
lexicographic key range `[start, end)`. Let `d` be the first key column whose
start and end differ; the boxes are the lower tail (`c_d = start_d` and the
suffix `>=` the lower suffix), the middle (`start_d < c_d < end_d`) and the upper
tail (`c_d = end_d` and the suffix `<` the upper suffix), i.e. `2*(n-d)+1` boxes
for an `n`-column key (a three-column key uses 5, 3 or 1 boxes). The final
chunk's last upper bound is `<=` so the captured maximum row is included; a key
that differs only in its last column is a single scalar range and a key whose
bounds are all equal is a single row. Each box is a prefix of equalities plus a
range on the next column, so PostgreSQL estimates it column by column and uses
the primary-key index, where a single row-value comparison is mis-estimated and
can sequential-scan the whole table. Each
`i_key_kinds` entry must be `bigint`, `text` or `uuid` and is interpolated as the
literal's cast, so any other value raises `22023`; the arrays must all have the
same length, and a NULL start or end value raises `22023` (a NULL would render
as an unquoted `NULL`, making the predicate match no rows). The start and end
values are the text form of the packed boundary key. Identifiers are quoted with
`%I` and values with `%L`, so neither substitution can reintroduce a token. It
is the one-shot pair of `prepare_chunk_sql_render` and
`render_prepared_chunk_sql`; a caller that renders many chunks for one table
calls those two directly and prepares once.

###

`dml_utils_lib.prepare_chunk_sql_render(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_primary_key_columns, i_key_kinds)`

```sql
i_sql_text            dml_utils_data.non_null_text
i_schema_name         dml_utils_data.non_null_text
i_table_name          dml_utils_data.non_null_text
i_table_alias         dml_utils_data.non_null_text
i_primary_key_columns name[]
i_key_kinds           text[]
RETURNS record
    (o_template text, o_column_names text[], o_key_collations text[])
```

`STABLE`, `SECURITY INVOKER`. Validates the chunking template (see
`assert_chunking_template`) and the primary-key columns and kinds once, and
returns the run-invariant render context for `render_prepared_chunk_sql`: the
template with `<driving_table>` already substituted and `<chunking_clause>`
replaced by an internal sentinel, the alias-qualified quoted column names, and
each text column's collation (the quoted collation name, `default`, or NULL when
the column is not in the catalog, in which case the per-chunk render compares
under the default operator). It reads the catalog for the collations and rejects
an unsupported kind, a NULL/empty column name, a malformed columns/kinds array, a
template that is not a valid chunking template, or an identifier/template
containing a reserved substitution character (`22023`). Prepare once per run and
pass the result to `render_prepared_chunk_sql` for every chunk.

###

`dml_utils_lib.render_prepared_chunk_sql(i_template, i_column_names, i_key_kinds, i_key_collations, i_start_values, i_end_values, i_is_final)`

```sql
i_template       text
i_column_names   text[]
i_key_kinds      text[]
i_key_collations text[]
i_start_values   text[]
i_end_values     text[]
i_is_final       boolean
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Renders one chunk from the context returned by
`prepare_chunk_sql_render`: it validates and casts the start/end boundary values,
builds the same axis-aligned box disjunction as `render_chunk_sql`, and
substitutes it for the prepared template's chunking-clause sentinel. It reads no
catalog, so calling it per chunk avoids repeating the template validation and
quoting that `render_chunk_sql` would redo. A NULL template, a prepared template
without the clause sentinel, a NULL `i_is_final`, malformed arrays, a NULL or
invalid-for-its-kind boundary value, or a reversed/empty non-final range raises
`22023`.

### `dml_utils_lib.assert_no_active_run_for_label(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Raises `unique_violation` (`23505`) when a not-archived
migration run already exists for the label.

###

`dml_utils_lib.populate_migration_boundaries(i_schema_name, i_table_name, i_label, i_sql_text, i_chunk_size [, i_threads, i_chunk_by, i_driving_table_alias, i_sleep_ms])`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_label       dml_utils_data.non_null_text
i_sql_text    dml_utils_data.non_null_text
i_chunk_size  dml_utils_data.positive_integer
i_threads     dml_utils_data.positive_integer DEFAULT 1
i_chunk_by    dml_utils_data.chunking_strategy DEFAULT 'primary_key'
i_driving_table_alias dml_utils_data.non_null_text DEFAULT 't'
i_sleep_ms    dml_utils_data.non_negative_integer DEFAULT 0
RETURNS bigint
```

`SECURITY DEFINER`, with a pinned `search_path`. Creates a
`dml_utils_data.migration_run` row for the label, with the recorded SQL text,
chunk size, strategy, threads, per-chunk sleep, driving table and driving table
alias (storing the alias and sleep at creation so a resume uses the same values),
records the run's `started_at` (the run start, when the calculation begins), then
populates the boundaries for the strategy and records `boundaries_calculated_at`
(when the range calculation finishes) and `total_chunks` (the boundary count minus
the terminal high-water boundary, clamped to 0 for an empty source); returns the
new `run_id`. For `primary_key`
it inserts one fixed-row chunk boundary per chunk plus a terminal high-water
boundary at the captured maximum primary key; for `blocks` it inserts one start
boundary every `i_chunk_size` heap blocks plus a one-past-end terminal boundary
and records the driving table's physical filepath. Both timestamps are written in
this worker's transaction, so they commit with the run and persist across a
failed processing attempt. For `primary_key` the source table must exist and have
a primary key of one to three columns, each of a supported type (`smallint`,
`integer`, `bigint`, `text` or `uuid`); the columns are identified from the
catalog in key order, not assumed to be `id`. For `blocks` the table must be a
plain heap. Each boundary is stored as
`dml_utils_data.migration_key`, which holds position-aligned arrays — index i is
the value of primary-key column i in the array matching that column's kind, and
exactly one array element is non-NULL per index. The `migration_boundary_key_check`
constraint enforces that: each present array must have a non-NULL element (so an
all-NULL or empty array is rejected), present arrays must share one length of one
to three, and exactly one of the three arrays holds a non-NULL value at each
index. Raises `23505` when
an active (not archived) run already exists for the label. The
`migration_boundary` rows are inserted with their own `started_at` and
`completed_at` null. An empty source produces a run with no boundaries. Later
inserts above the captured maximum fall outside the terminal boundary and are not
processed. Because the definer owner scans the driving table, the function checks
the effective caller's `SELECT` privilege first (`42501` otherwise) and the owner
must also be able to read that table.

###

`dml_utils_lib.build_boundary_population_sql(i_schema_name, i_table_name, i_key_columns, i_key_kinds, i_run_id, i_chunk_size)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_key_columns name[]
i_key_kinds   text[]
i_run_id      bigint
i_chunk_size  bigint
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Returns the statement that scans the driving
table in boundary-key order and inserts the run's chunk boundaries as the
contiguous sequence `0..N` (one start per chunk plus the terminal high-water
boundary). The key may be the primary key or a caller-selected ordered key.
`populate_migration_boundaries` executes it and the explain path explains it, so
both use the identical SQL; the run id and chunk size are inlined (both bigint).
Identifiers are quoted with `%I` and every kind is checked against the
`bigint`/`text`/`uuid`/`timestamptz` whitelist (`22023` otherwise).

### `dml_utils_lib.synthetic_chunk_boundary_values(i_key_kinds, i_chunk_size)`

```sql
i_key_kinds  text[]
i_chunk_size bigint
RETURNS TABLE
    (o_is_final boolean, o_start_values text [], o_end_values text [])
```

`IMMUTABLE`, `SECURITY INVOKER`. Returns two representative chunk ranges, one
per key kind and in key order: the non-final (`o_is_final` false) and final (`o_is_final` true) rows. `bigint` spans
`0..chunk_size`, `text` uses `a..b` and
`uuid` the first two all-zero forms. The values are synthetic, so the explain
path can render a plausible chunk predicate without reading real boundaries.
Rejects malformed kinds or a non-positive chunk size (`22023`).

### `dml_utils_lib.explain_query_plan(i_sql_text)`

```sql
i_sql_text text
RETURNS json
```

`VOLATILE`, `SECURITY INVOKER`. Returns `EXPLAIN (FORMAT JSON)` of `i_sql_text`
as a json value, without executing it (no `ANALYZE`): an INSERT or UPDATE only
plans. Rejects a NULL, empty or blank statement (`22023`). It is `VOLATILE`
because PostgreSQL forbids `EXPLAIN` in a non-volatile function.

###

`dml_utils_lib.explain_chunk_plans(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_primary_key_columns, i_key_kinds, i_chunk_size [, i_chunk_by])`

```sql
i_sql_text            dml_utils_data.non_null_text
i_schema_name         dml_utils_data.non_null_text
i_table_name          dml_utils_data.non_null_text
i_table_alias         dml_utils_data.non_null_text
i_primary_key_columns name[]
i_key_kinds           text[]
i_chunk_size          bigint
i_chunk_by            dml_utils_data.chunking_strategy DEFAULT 'primary_key'
RETURNS TABLE
    (o_plan_kind text, o_sql_text text, o_plan json)
```

`VOLATILE`, `SECURITY INVOKER`. Renders and explains the three statements a
chunked run generates — the boundary-population insert (synthetic run id `0`), a
non-final chunk and the final chunk — using synthetic ranges and reading no
boundaries. For `primary_key` the boundary plan is the primary-key boundary
insert and the chunk plans are a `<` non-final and a `<=` final range; for
`blocks` the boundary plan is the block boundary insert and both chunk plans are
half-open ctid ranges. Shared by `explain_migration_chunks` and
`explain_function_over_table`.

###

`dml_utils_lib.explain_chunk_plan(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_primary_key_columns, i_key_kinds, i_start_values, i_end_values, i_is_final)`

```sql
i_sql_text            dml_utils_data.non_null_text
i_schema_name         dml_utils_data.non_null_text
i_table_name          dml_utils_data.non_null_text
i_table_alias         dml_utils_data.non_null_text
i_primary_key_columns name[]
i_key_kinds           text[]
i_start_values        text[]
i_end_values          text[]
i_is_final            boolean
RETURNS TABLE
    (o_plan_kind text, o_sql_text text, o_plan json)
```

`VOLATILE`, `SECURITY INVOKER`. The explicit-boundary companion to
`explain_chunk_plans`: it renders one primary-key chunk from the supplied
boundaries and returns its `EXPLAIN (FORMAT JSON)` plan as a single
`chunk_non_final` or `chunk_final` row. The range is validated and cast by
`render_chunk_sql`, so a reversed or empty range is rejected with `22023`. It is
the shared implementation of `dml_utils.explain_migration_chunk` and
`dml_utils.explain_function_over_table_chunk`.

### `dml_utils_lib.build_block_boundary_population_sql(i_schema_name, i_table_name, i_run_id, i_chunk_size)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_run_id      bigint
i_chunk_size  bigint
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Returns the statement that inserts a block-chunked
run's boundaries as the contiguous sequence `0..N`: one start boundary every
`i_chunk_size` heap blocks plus a one-past-end terminal boundary, each a bigint
block number in `migration_key`. The block count comes from `pg_relation_size`
and the server block size at execution time, so no table scan is needed; a table
with no blocks yields no boundaries. See the block-strategy limitations above.

###

`dml_utils_lib.render_block_chunk_sql(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_start_block, i_end_block)`

```sql
i_sql_text     dml_utils_data.non_null_text
i_schema_name  dml_utils_data.non_null_text
i_table_name   dml_utils_data.non_null_text
i_table_alias  dml_utils_data.non_null_text
i_start_block  bigint
i_end_block    bigint
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Substitutes `<driving_table>` and a half-open ctid
range for `<chunking_clause>`, for example
`((t.ctid) >= ('(0,0)'::tid) AND (t.ctid) < ('(1000,0)'::tid))`. Every chunk,
including the terminal one, uses a half-open range; the terminal end is the
one-past-end block count. Rejects a NULL, reversed or empty range and a bad
template (`22023`).

### `dml_utils_lib.relation_filepath(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Returns the table's `pg_relation_filepath` (its
physical file, relative to the data directory), the fingerprint a block run
records; raises `undefined_table` (`42P01`) when the table does not exist.

### `dml_utils_lib.assert_relation_filepath(i_schema_name, i_table_name, i_expected_filepath)`

```sql
i_schema_name       dml_utils_data.non_null_text
i_table_name        dml_utils_data.non_null_text
i_expected_filepath text
RETURNS void
```

`STABLE`, `SECURITY INVOKER`. Raises `invalid_parameter_value` (`22023`) when the
table's current filepath differs from the recorded one, so a block-chunked run
fails closed after a heap rewrite instead of resuming stale block boundaries. A
NULL expectation (a primary-key run) is a no-op.

### `dml_utils_lib.process_migration_chunk(i_run_id, i_boundary_no, i_sql_text)`

```sql
i_run_id      bigint
i_boundary_no bigint
i_sql_text    dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER` for the chunk SQL. Claims one boundary through the
owner-owned `claim_migration_boundary` helper (which locks the row for the
caller's transaction and returns the claim timestamp), runs the fully-formed
chunk SQL as the caller, then completes the boundary through the owner-owned
`complete_migration_boundary` helper, which writes both timestamps and advances
the run's `total_chunks_completed` in that same transaction. Raises
`P0C01` when the boundary does not exist, is already completed or was cancelled.
If the chunk SQL fails, the transaction aborts and the claim rolls back —
including `started_at` — so the chunk is retried on the next run. A boundary
therefore reports `started_at` only for a chunk that actually completed; this is
intended. Intended to run inside a `pg_background` worker, one call per chunk.

### `dml_utils_lib.record_migration_error(i_run_id, i_boundary_no, i_sqlstate, i_message)`

```sql
i_run_id      bigint
i_boundary_no bigint
i_sqlstate    dml_utils_data.non_null_text
i_message     dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Inserts one row into `dml_utils_data.migration_error` for a
failed chunk worker. Intended to run inside a `pg_background` worker so the row
commits autonomously; `run_migration_chunks` calls it that way before re-raising
a chunk failure, so the error outlives the aborted caller transaction.

## `dml_utils_data`

### `dml_utils_data.set_updated_at()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`. The shared `BEFORE UPDATE ... FOR EACH ROW` trigger function
that stamps `NEW.updated_at := now()` on every table, so no caller can bypass
it. Attach it to each table with a trigger named `<table>_set_updated_at`; see
`changes/sql_changes/006-create-migration-tables.sql`.

### `dml_utils_data.reject_migration_run_update()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`, not callable by users (PUBLIC `EXECUTE` is revoked and it is
granted to no one). The `BEFORE UPDATE ... FOR EACH ROW` trigger on
`migration_run` that rejects any change to `label`, `chunk_size` or the driving
table (`driving_table_schema_name`, `driving_table_name`), all fixed when the run
is created, and to `total_chunks` once `boundaries_calculated_at` is set (the two
are written together); `total_chunks_completed` stays mutable for the chunk
workers.

### `dml_utils_data.reject_migration_boundary_update()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`, not callable by users (PUBLIC `EXECUTE` is revoked and it is
granted to no one). The `BEFORE UPDATE ... FOR EACH ROW` trigger on
`migration_boundary` that rejects any change to `boundary_no` or `boundary_id`,
both fixed when the boundaries are computed.
