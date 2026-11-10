# dml_utils

## Purpose

dml_utils rewrites large PostgreSQL tables without one long-running statement (and one long transaction) on the table.
You hand it a DML template — anything PostgreSQL can run, including `UPDATE`,
`DELETE` or `INSERT ... SELECT` with joins and subqueries — and it
applies that template over the table in fixed-row chunks, each committed in its
own background worker, so a multi-million-row backfill makes durable progress
and a re-run resumes where it stopped (see
[Processing a table in chunks](#processing-a-table-in-chunks)). Chunks can be
processed in parallel by more than one worker.

It works on a table with a **primary key of up to three columns**, each of type
`smallint`, `integer`, `bigint`, `text`, `uuid`, `timestamptz` or `bytea` (the
column names need not be `id`); the chunks are primary-key ranges, so the
planner can use the primary-key index.

It is packaged as Liquibase-managed SQL, so installing it means applying the
bundled changelog with the Liquibase CLI — there is nothing to build and no
Docker required (see
[Installing with the Liquibase CLI](#installing-with-the-liquibase-cli)).

## What is in the box

Liquibase loads three application schemas, layered so nothing lower depends on
anything above it:

- **`dml_utils`** — the caller-facing API: `dml_utils.run_migration_chunks` and
  the `run_function_over_table` wrapper, plus the run controls (`set_migration_run_sql_text`,
  `set_migration_run_function`,
  `set_migration_run_threads`, `set_migration_run_sleep_ms`, `archive_migration_run`,
  `cancel_migration_run`), the read-only explain
  helpers (`explain_migration_chunks`/`explain_migration_chunk` and
  `explain_function_over_table`/`explain_function_over_table_chunk`) and the
  inspection and cleanup helpers (`migration_run_summary`, `migration_boundaries`,
  `migration_errors`, `delete_archived_migration_runs`). It depends on the two
  schemas below.
- **`dml_utils_lib`** — the engine: the generic catalog and template helpers and
  the internal routines that populate boundaries, run one chunk and record
  errors. It may use `dml_utils_data`, never `dml_utils`.
- **`dml_utils_data`** — the data layer: the shared domains (`non_null_text`,
  `positive_integer`), the `migration_key` composite type that packs a
  boundary's key value (position-aligned arrays, one per key kind, including
  `timestamptz` and `bytea`), and the fixed-row chunk migration tables
  `migration_run` / `migration_boundary` (`migration_error` records failed
  chunks), plus their trigger functions.

Liquibase's own tracking tables are kept out of all three schemas: they live in
a dedicated `liquibase` schema as `liquibase.dml_utils_databasechangelog` and
`liquibase.dml_utils_databasechangeloglock`.

Every table carries `created_at`/`updated_at` (both `timestamptz NOT NULL
DEFAULT now()`), and the shared `dml_utils_data.set_updated_at()` trigger keeps
`updated_at` current on every `UPDATE`, so a caller cannot bypass it.

## Processing a table in chunks

`dml_utils.run_migration_chunks` runs a DML statement over a large table in
fixed-row chunks, one `pg_background` worker per chunk, so a long-running
backfill does not hold one giant statement (and one long transaction) on the
table. Each worker commits its own chunk, so progress is durable and a re-run
resumes where it stopped.

The chunks can be processed in parallel: `i_threads` (default `1`) sets how many
workers run at once, so a run can use several CPU cores and finish sooner on a
fast storage system. It is not free — parallel chunks contend for the same
table's locks and pages, and it must not exceed `max_worker_processes` — so tune
it to the workload (see [How it works](#how-it-works)).

### Why pg_background

The work is done through [`pg_background`](https://github.com/vibhorkum/pg_background),
which runs SQL in a background worker with an **autonomous transaction**: a
worker's `COMMIT` is independent of the caller's transaction, so a chunk is
committed while the calling `run_migration_chunks` is still running, and each
worker is a separate backend.

This could instead be a stored procedure that commits between chunks — functions
cannot commit, so a procedure is the only in-database alternative. In practice
that bloats the driving table badly. The whole procedure runs in one backend,
and a backend's dead tuples are not reported to the shared buffer for reuse
until its top-level call completes, so every chunk's old row versions pile up
and `VACUUM` cannot reclaim them until the entire run returns. The end result is
a table bloated by the full migration rather than one chunk at a time. Because
each `pg_background` worker is its own backend, its dead tuples become reclaimable
as soon as that chunk commits, so the bloat stays bounded by a single chunk.

### How it works

You supply a SQL **template** with two placeholders that must each appear
exactly once:

- `<driving_table>` — replaced by `"<schema>"."<table>" "<alias>"`.
- `<chunking_clause>` — replaced by the chunk's key range over the order chosen
  by `i_chunk_by` (see [Choosing the chunking key](#choosing-the-chunking-key)).
  The predicate is a disjunction of axis-aligned boxes, so a composite key
  compares column by column and the planner can use the key index. Every start
  and end is an explicitly cast literal; the upper bound is `<` for every chunk
  except the last, which uses `<=` so the captured maximum row is included.

For the default `primary_key` strategy, the driving table must have a **primary
key of up to three columns**, each of type `smallint`, `integer`, `bigint`, `text`,
`uuid`, `timestamptz` or `bytea`; the column names are read from the catalog, so
they need not be `id`.
The predicate compares the key tuple to explicitly cast literals (`(t.id) >= ('1'::bigint)`, or
`(t.a, t.b) >= ('1'::bigint, 'x'::text)` for a
composite key) so the planner can use the primary-key index. Chunks are cut by
row number over the primary-key order; rows inserted later with keys above the
captured maximum are outside the final chunk and are not processed.
`ordered_key` uses the named unique index instead of the primary key, and
`blocks` uses physical heap ranges.

The first call for a `label` computes the boundaries (via
`populate_migration_boundaries`) and then processes them. Re-running with the
same `label` is safe: it resumes at the first unprocessed chunk using the SQL,
chunk size and driving table recorded when the run was created (a differing
`i_sql_text`, `i_chunk_size` or `i_driving_table_*` is ignored, with a notice),
and a run that is already complete is a no-op. To change the SQL of an existing run deliberately, use
`dml_utils.set_migration_run_sql_text`. Progress and completion are visible in
`dml_utils_data.migration_run` and `dml_utils_data.migration_boundary`.

### Choosing the chunking key

`i_chunk_by` selects the order the rows are cut in:

| `i_chunk_by`            | Order                                | Key                     |
|-------------------------|--------------------------------------|-------------------------|
| `primary_key` (default) | primary-key order                    | the table's primary key |
| `ordered_key`           | a caller-selected unique index order | `i_boundary_key_index`  |
| `blocks`                | physical heap order                  | `ctid` ranges           |

Use `primary_key` when the primary key is a useful order (a sequence, a time, a
natural key). Use `ordered_key` when the primary key is random — for example a
UUIDv4 — or when a separate index is the order the workload actually uses, such
as `(created_at, id)`. `blocks` is a physical fallback with strong
preconditions; see `dml_utils/src/main/resources/db/changelog/changes/functions/README.md`
for its limitations.

Any of the three strategies can also be applied **partition-wise** to a
declarative partitioned parent by adding `i_partition_wise => true`: `i_chunk_by`
then selects the strategy used *inside each leaf*, and every chunk is rendered
against its leaf. See [Partition-wise chunking](#partition-wise-chunking).

#### Ordered-key chunks

An ordered key is a **unique btree index on the driving table**. Naming the
index rather than passing a column list is deliberate: the catalog must prove
that the order exists, and the run records the resolved descriptor so resume and
EXPLAIN use exactly the same key.

The index must be:

- a valid, live, unique btree index on a plain table, or for a partition-wise run
  the matching unique btree index on the partitioned parent (every leaf's child
  index is validated);
- one to three **simple** key columns — no expressions, no partial index, no
  exclusion constraint — and every key column must be `NOT NULL`;
- ascending with `NULLS LAST`, using the type's default btree operator class and
  the column's own collation;
- on key columns of type `smallint`, `integer`, `bigint` (all collapsed to
  `bigint`), `text`, `uuid`, `timestamptz` or `bytea`.

The order is lexicographic. A one-column key is a scalar range; a composite key
compares column by column, so a second column (for example `id`) is the
tiebreaker for duplicate leading values. A non-unique index plus a separate
tiebreaker is not supported; use a composite unique index such as
`(created_at, id)`.

The key columns must not be modified by the chunk SQL: the boundaries are values
of that key, so mutating it can move a row into a chunk that was already
processed, or out of one that was not. This is a caller contract, not detected;
archive and restart the run if the key is mutated. A resumed ordered run
re-validates the stored index and descriptor and fails with `55000` if the index
was dropped, redefined or is no longer usable.

Non-goals: chunking by hash value, non-unique keys without a composite unique
index, expression/partial/exclusion indexes, non-default operator classes,
`DESC`/`NULLS FIRST`, `date`/`numeric` keys and `run_function_over_table`.
Partition-wise chunking supports range, list, hash and multi-level partitioned
parents (see below).

Example: a UUIDv4 primary key with a useful time/ID order:

```sql
CREATE TABLE app.orders
(
    id           uuid PRIMARY KEY,
    created_at   timestamptz NOT NULL,
    processed_at timestamptz
);

CREATE UNIQUE INDEX orders_created_at_id_key
    ON app.orders (created_at, id);

SELECT dml_utils.run_migration_chunks(
               i_sql_text =>
                   'UPDATE <driving_table> SET processed_at = now() WHERE <chunking_clause>',
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'orders',
               i_label => 'orders-processed',
               i_chunk_size => 10000,
               i_threads => 4,
               i_chunk_by => 'ordered_key',
               i_boundary_key_index => 'orders_created_at_id_key');
```

`i_boundary_key_index` is required for `ordered_key` and rejected for the other
strategies. To inspect the plan without running anything, pass the same
`i_chunk_by` and `i_boundary_key_index` to
`dml_utils.explain_migration_chunks`; for one real chunk, use
`dml_utils.explain_migration_chunk` with the chunk's boundary values and the
same key selection. See `docs/adr/0001-ordered-key-chunking.md` for the design.

### Partition-wise chunking

A normal run treats the driving table as one relation. For a declarative
partitioned table, that means the parent plan touches every partition and the
range predicate is not a partition-pruning constraint. Set
`i_partition_wise => true` to enumerate the storage leaves, run the chosen
`i_chunk_by` strategy inside each leaf, and render every chunk against its leaf:

```sql
CREATE TABLE app.events
(
    id         bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    payload    jsonb,
    PRIMARY KEY (created_at, id)
) PARTITION BY RANGE (created_at);

CREATE TABLE app.events_2026_01 PARTITION OF app.events
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE app.events_2026_02 PARTITION OF app.events
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

SELECT dml_utils.run_migration_chunks(
               i_sql_text =>
                   'UPDATE <driving_table> SET payload = payload WHERE <chunking_clause>',
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'events',
               i_label => 'events-partition-backfill',
               i_chunk_size => 10000,
               i_threads => 4,
               i_chunk_by => 'primary_key',
               i_partition_wise => true);
```

`i_partition_wise` is a modifier, not a fourth `i_chunk_by` value: `primary_key`,
`ordered_key` and `blocks` all work inside leaves. The parent must be a
declarative partitioned table (`relkind = 'p'`); range, list, hash and
multi-level (sub-partitioned) trees are supported. Leaves must be ordinary
tables — a foreign-table partition is rejected — and a `blocks` run requires each
leaf to be a plain heap. Ordered-key runs resolve the parent unique index and
validate every leaf's matching child index before the run is created.

The leaf order is deterministic: partition-bound expression, then schema, name
and OID, compared byte-wise. That order is the manifest contract for boundary
numbering, resume and EXPLAIN, so the same tree always produces the same leaf
ordinals. An empty leaf is still a manifest row: it is calculated with zero
boundaries rather than omitted. If every leaf is empty, the run has
`total_chunks = 0` and no terminal boundary.

**Manifest and resume.** Creating a partition-wise run first commits the run and
one `dml_utils_data.migration_run_leaf` row per leaf (ordinal, schema/name/OID,
partition bound, per-leaf block filepath, state). Boundaries are then calculated
one leaf per worker transaction, in ordinal order; each leaf's starts are appended
after the previous leaf's, and its high-water key is snapshotted. A failure
leaves the earlier leaves committed and the failed leaf marked `failed` with its
SQLSTATE and message; `boundaries_calculated_at` stays NULL. Re-running the same
label resumes at the first `pending` or `failed` leaf instead of processing; a
`calculated` leaf is never recalculated. Inspect progress with:

```sql
SELECT leaf_ordinal,
       leaf_schema_name,
       leaf_name,
       state,
       boundary_count,
       calculated_at,
       error_sqlstate,
       error_message
FROM dml_utils_data.migration_run_leaf
WHERE run_id = 42
ORDER BY leaf_ordinal;
```

**Changed tree.** Before continuing calculation and before processing, the live
leaf set is compared with the manifest: ordinal, schema, name, OID, partition
bound and (for `blocks`) the leaf's physical filepath. Any difference fails
closed with `55000` and leaves existing progress committed; the stored ranges are
stale, so archive the run and start a new one with a new label. Do not try to
attach/detach or rebind a partition while a partition-wise run is calculating or
processing.

**Cancellation.** `cancel_migration_run` during calculation stops launching new
leaves; leaves already calculated keep their boundaries and the remaining leaves
stay `pending`. The run is terminal — archive it and start a new one rather than
resuming it.

**Explain.** A rendered chunk targets exactly its leaf (no parent `Append`). For
a primary-key run the leaf has the same key columns, so
`explain_migration_chunk` can be given the leaf's schema/name. Ordered-key and
blocks leaf plans use the renderer/EXPLAIN helpers with the leaf; the public
explain calls do not yet take a partition-wise selector. A `blocks` leaf chunk is
a ctid range on that leaf's heap.

`run_function_over_table` is not partition-wise in this version; use
`run_migration_chunks` with an `UPDATE`/`DELETE` template for partitioned tables.

### Example: backfill a column

Given a table with a single `bigint` primary key (any supported key type works
the same way):

```sql
CREATE TABLE app.events
(
    id      bigint PRIMARY KEY,
    payload jsonb,
    region  text
);
```

Backfill `region` in chunks of 10,000 rows:

```sql
SELECT dml_utils.run_migration_chunks(
               i_sql_text =>
                   'UPDATE <driving_table> SET region = ''unknown'' WHERE <chunking_clause>',
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'events',
               i_label => 'events-region-backfill',
               i_chunk_size => 10000);
```

`i_chunk_size` defaults to `1000` and must be positive; omit it to use the
default. `i_threads` defaults to `1` and processes that many chunks in parallel (each in its own worker); if one chunk
fails, the call stops launching new
chunks, lets the in-flight ones commit, and then raises the error, leaving the
run to be resumed. It must not exceed `max_worker_processes`, and parallel
chunks still contend for the same table's locks and pages, so it is not always
faster. The thread count is recorded on the run like the SQL text: a resumed
run uses the recorded value (a differing `i_threads` is ignored, with a notice),
and you can change it on an unfinished run with
`dml_utils.set_migration_run_threads(i_label, i_threads)`.

To slow a run instead of speeding it up, set `i_sleep_ms` (default `0`). It is
typed by the `dml_utils_data.non_negative_integer` domain, so `0` is a valid
no-op but NULL and negative values are rejected. It is the number of milliseconds
the coordinator waits after each successfully completed chunk before launching
another, which reduces the chunk completion rate and therefore WAL generation. The final chunk does not sleep, and the
wait is interrupted promptly by cancellation. The value is recorded on the run
like the thread count: a resumed run uses the stored value and a differing
`i_sleep_ms` is ignored with a notice. Change it on an unfinished run with
`dml_utils.set_migration_run_sleep_ms(i_label, i_sleep_ms)`; that affects the
next call, not a coordinator already processing the run.

The sleep is per completed chunk, not a global rate limit: with `i_threads`
workers, up to that many chunks can still be in flight, and a large chunk still
generates its WAL in one burst. For strict pacing, use a small thread count
and/or smaller chunks in addition to the sleep. A sleep of `0` is a no-op.

The worker SQL for the first chunk is:

```sql
UPDATE "app"."events" "t"
SET region = 'unknown'
WHERE ((t.id) >= ('1'::bigint) AND (t.id) < ('10001'::bigint))
```

and the final chunk uses `(t.id) <= ('<max>'::bigint)`.

### Example: a custom alias

If the template refers to the driving table more than once, give it an alias so
every reference resolves:

```sql
SELECT dml_utils.run_migration_chunks(
               i_sql_text =>
                   'UPDATE <driving_table> SET payload = payload || ''{"migrated":true}'''
                       ' WHERE <chunking_clause>',
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'events',
               i_label => 'events-payload-migrate',
               i_chunk_size => 5000,
               i_driving_table_alias => 'e');
```

### Example: running a function over every row

If a developer finds the template SQL hard to write, they can encapsulate the
per-row work in a `void` function whose arguments are the driving table's
primary-key columns, in key order, and let
`dml_utils.run_function_over_table` build the template and run it chunk by chunk:

```sql
CREATE FUNCTION app.review_dog(p_breed text)
    RETURNS void
    LANGUAGE sql AS
$$
UPDATE app.dogs
SET status = 'reviewed'
WHERE breed = p_breed;
$$;
```

```sql
SELECT dml_utils.run_function_over_table(
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'dogs',
               i_function_schema_name => 'app',
               i_function_name => 'review_dog',
               i_chunk_size => 1000);
```

The wrapper validates the function up front (it must return `void` and take the
primary-key column types in key order, raising `22023` otherwise), derives a
resume label from the table and function, and delegates to
`run_migration_chunks`. Point an unfinished run at a different function with
`dml_utils.set_migration_run_function`. See `examples/dog_breeds_function/` for a
full runnable script.

### Requirements and behavior

- `pg_background` must be installed and the caller must hold
  `pgbackground_role` (the custom image's init script grants it to
  `dml_utils_caller`).
- `run_migration_chunks` and `process_migration_chunk` are `SECURITY INVOKER`:
  the chunk SQL runs with the caller's privileges and needs whatever privileges
  that SQL needs on the driving table (typically `UPDATE`). The caller role is
  granted only `USAGE`/`EXECUTE` plus `SELECT` on the migration metadata; grant
  application-table privileges to the application/login role that runs the
  migration.
- `ordered_key` additionally requires the unique btree index described in
  [Ordered-key chunks](#ordered-key-chunks), and the caller needs
  `SELECT` on the driving table for the boundary scan plus whatever the chunk SQL
  needs. The run stores the resolved descriptor; a resume re-validates it and
  fails with `55000` if the index changed. The chunk SQL must not modify the key
  columns.
- Metadata writes are delegated to owner-owned `SECURITY DEFINER` helpers (`claim_migration_boundary`,
  `complete_migration_boundary`,
  `complete_migration_run`, `mark_migration_run_cancelled`, and
  `record_migration_error`) so the invoker-owned worker does not need direct
  DML on `dml_utils_data`. Those helpers are callable by the caller because the
  same role runs the invoker-owned SQL; this centralizes writes and prevents
  accidental direct metadata DML, but it is **not a hard authorization boundary
  against a malicious template**. Use trusted callers or a parser/template
  allowlist if the SQL is untrusted.
- `populate_migration_boundaries` is owner-owned so it can write metadata, but
  it explicitly checks the caller's `SELECT` privilege on the driving table
  before scanning it. The owner role must also be able to `SELECT` the driving
  table (grant it if the application owns that table); the caller check prevents
  using the definer context to read a table the caller cannot read directly.
- The routines are not an authorization boundary by design. `dml_utils_caller`
  is granted only the `EXECUTE`/`USAGE`/`SELECT` it needs (see
  `999-grant-privileges.sql`), and `ALTER DEFAULT PRIVILEGES` keeps future
  objects off `PUBLIC`; security comes from the grant set and the application
  role's privileges, not from per-call checks.
- Run under `READ COMMITTED`, and do not hold locks (or uncommitted writes) on
  the driving table across the call: a worker that needs a row the caller holds
  cannot make progress.
- The chunk SQL should be idempotent: a chunk whose worker failed is retried on
  the next run, and a chunk that committed is not run again.

### Inspecting and restarting a run

Helper functions summarise a label's runs, and list a run's boundaries and
errors by run id:

```sql
-- One row per run for the label, with boundary and error counts, and whether it
-- is done (completed_at), cancelled (cancelled_at) or archived (archived_at).
-- Take the run_id from here.
SELECT *
FROM dml_utils.migration_run_summary(i_label => 'events-region-backfill');

-- Per-chunk progress (completed_at IS NULL means still to do). boundary_id is a
-- migration_key: position-aligned arrays, one per key kind. Index i is the i-th
-- key column, so (boundary_id).bigint_values[1] reads the first column
-- of a bigint key.
SELECT boundary_no, (boundary_id).bigint_values[1], completed_at
FROM dml_utils.migration_boundaries(i_run_id => 42);

-- The errors recorded for the run, so it can be diagnosed without the worker
-- logs.
SELECT boundary_no, sqlstate, message, created_at
FROM dml_utils.migration_errors(i_run_id => 42);
```

To inspect the plan for one real chunk (rather than the synthetic representative
ranges), supply its start and end boundary values — one text value per key
column, in key order — and whether it is the final chunk:

```sql
SELECT o_plan_kind, o_sql_text, o_plan
FROM dml_utils.explain_migration_chunk(
        i_sql_text => 'UPDATE <driving_table> SET ... WHERE <chunking_clause>',
        i_driving_table_schema_name => 'public',
        i_driving_table_name => 'events',
        i_start_values => ARRAY ['0'],
        i_end_values => ARRAY ['1000'],
        i_is_final => false);
```

For an ordered-key chunk, pass the same `i_chunk_by => 'ordered_key'` and
`i_boundary_key_index` used by the run.

For a partition-wise run, the query above shows chunk progress; the leaf
manifest shows calculation progress. Re-calling `run_migration_chunks` with the
same label resumes a partially calculated run at its first `pending`/`failed`
leaf; a changed partition tree is rejected with `55000`, so archive the run and
use a new label (see [Partition-wise chunking](#partition-wise-chunking)).

`dml_utils.explain_function_over_table_chunk` is the equivalent for a
`run_function_over_table` call. Neither has a `blocks` form: a block chunk is a
ctid range, so the synthetic explain already shows the same shape.

To stop an in-flight run, cancel it from another session:

```sql
SELECT dml_utils.cancel_migration_run(i_label => 'events-region-backfill');
```

The coordinator stops launching, cancels the chunks already running, and marks
the run and its remaining boundaries cancelled. A cancelled run is terminal:
archive it before starting a new run with the same label.

To re-run a label from scratch (for example after changing the chunk size),
archive the current run first; then the label is free again:

```sql
SELECT dml_utils.archive_migration_run(i_label => 'events-region-backfill');
```

Archived runs can be deleted — all of them, or just one label's:

```sql
SELECT dml_utils.delete_archived_migration_runs();
SELECT dml_utils.delete_archived_migration_runs(i_label => 'events-region-backfill');
```

## Requirements

- PostgreSQL 16, 17, 18 or 19 (CI builds and tests all four; 19 is tested as
  `19rc1` until its GA, and 17 is the default).
- Java 25 and the Maven Wrapper (`./mvnw`) for the build and tests.
- Docker for the jOOQ codegen and the integration tests (Testcontainers).

## ddl_utils dependency

dml_utils assumes **ddl_utils is installed in the same database**. Future
changes to the `dml_utils_data` tables use ddl_utils' lock-aware DDL helpers (for example `ddl_utils.add_column`)
instead of a raw `ALTER TABLE`, so a
migration does not hold an unbounded `ACCESS EXCLUSIVE` lock.

Install ddl_utils' changelog **before** dml_utils' changelog, and grant its
caller role to the role that runs the dml_utils migration:

```sql
GRANT ddl_utils_caller TO dml_utils_owner;
```

dml_utils' own changelog does not create ddl_utils; a database without it fails
at the first changeset that calls a helper (today `009-allow-bytea-ordered-key-kind`). The versioned
`io.github.eyupmiduck:ddl_utils` Maven artifact (`ddl-utils.version`) supplies
the changelog to the test suite and the jOOQ code generator (unpacked into
`target/classes/db/changelog-install/ddl-utils`), the local development stack (`scripts/postgres/migrate.sh`) and the
benchmark (`benchmark/setup.sh`). It is
used only by that test/codegen/local tooling and is not part of the published
jar.

The build resolves the artifact from GitHub Packages, so a local Maven build
needs a `github-ddl-utils` server (a token with `read:packages`) alongside the
existing `github` server in `~/.m2/settings.xml`. CI supplies both through
`.github/scripts/settings.xml`.

## Building and testing

```sh
scripts/build-postgres-image.sh postgres:17-alpine   # once, or after scripts/postgres/ changes
./mvnw verify
```

`verify` runs the unit tests, the container-backed integration tests, SQLFluff
over the changelog `.sql` files, and the Liquibase changelog linter. Docker must
be running.

- One test: `./mvnw -pl dml_utils -am test -Dtest=DomainTest`
- Skip SQLFluff: `./mvnw verify -Dskip.sqlfluff`
- Skip the changelog linter: `./mvnw verify -Dskip.liquibase-linter`

## Benchmark

The thread-scaling benchmark is self-contained under `benchmark/`:

```sh
benchmark/setup.sh    # create the dedicated database and fixture
benchmark/run.sh      # run the sweep configured in benchmark/config.env
benchmark/report.sh   # summarise the accumulated results
```

`BENCH_CHUNK_BY` selects `primary_key`, `ordered_key` or `blocks`, and
`BENCH_PARTITION_WISE=1` runs `primary_key` or `blocks` inside a range-partitioned
fixture with `BENCH_PARTITIONED_LEAVES` leaves. See
[`benchmark/README.md`](benchmark/README.md) for the parameters, the
partition-wise variant and a worked example.

## Local development database

```sh
scripts/build-postgres-image.sh postgres:17-alpine   # once
scripts/start-local-db.sh                            # up -d --wait
```

This starts a `dml-utils-postgres:17-alpine` container plus a one-shot Liquibase
service that applies the changelog as the `dml_utils_owner` role. Liquibase's
tracking tables live in the `liquibase` schema, created by the image's init
script. Connect with:

```sh
psql -h localhost -p 5433 -U postgres -d dml_utils   # password: postgres
```

The port is bound to `127.0.0.1` only and is `5433` here (set in the root
`.env`, so it can run alongside the ddl_utils dev database on `5432`); the
credentials and the stack are defined in `scripts/compose.yaml`. Data lives in
the `dml_utils_pgdata` volume: `scripts/stop-local-db.sh` keeps it, while
`scripts/refresh-local-db.sh` wipes it and re-runs the migrations.

## Installing with the Liquibase CLI

Installing dml_utils means applying the bundled Liquibase changelog to your
database. There is nothing to compile or package: clone the repository and point
the Liquibase CLI at `db.changelog-master.xml`.

### Prerequisites

- PostgreSQL 16, 17, 18 or 19 (CI builds and tests all four; 19 is tested as
  `19rc1` until its GA, and 17 is the default).
- Liquibase 5.0.x on your `PATH` (it needs Java 17+). Liquibase 5 ships without
  database drivers, so add the PostgreSQL driver once:

  ```sh
  liquibase lpm add postgresql --global
  ```

- A login role that owns the objects (`dml_utils_owner` below) with `CREATE` on
  the target database and schema.
- A role the routines and tables are granted to (`dml_utils_caller` below). The
  last changeset grants to it, so it must exist **before** you run the
  changelog. Applications connect as this role (or a role granted it).
- The [`pg_background`](https://github.com/vibhorkum/pg_background) extension,
  required by the chunking routines.

### 1. Create the roles

Connect to the target database as a superuser (or a role that can `CREATE ROLE`)
and create the owner and caller roles:

```sql
CREATE ROLE dml_utils_owner LOGIN PASSWORD 'change-me';
CREATE ROLE dml_utils_caller; -- no login; the routines are granted to it

GRANT CREATE ON DATABASE mydb TO dml_utils_owner;
\connect mydb
GRANT CREATE ON SCHEMA public TO dml_utils_owner;

-- Liquibase keeps its tracking tables in a dedicated schema and does not create
-- the schema itself, so create it here, owned by the role that runs the
-- migration.
CREATE SCHEMA liquibase AUTHORIZATION dml_utils_owner;
```

`dml_utils_owner` needs `CREATE` on the database and schema so Liquibase can
create its tracking tables and the `dml_utils`, `dml_utils_lib` and
`dml_utils_data` schemas. Never run the migration as `postgres`.

**Ownership policy.** The migration role is the permanent owner of the three
application schemas and their objects: it is the role enumerated in
`999-grant-privileges.sql` and in the `ALTER DEFAULT PRIVILEGES` statements, so
run every migration/upgrade as that same stable role (not a transient admin
account). `dml_utils_caller` holds `USAGE`/`EXECUTE` and `SELECT` on the
migration metadata, but no direct metadata DML, and can never `ALTER` or `DROP`
an object, so ownership stays separate from the caller grants. The application/
login role (a member of `dml_utils_caller`) needs the application-table
privileges the chunk SQL uses.
Creating objects otherwise (as a different role) leaves them with that role's
default privileges, which the least-privilege grants do not cover.

### 2. Run the changelog

From the repository root, connect as the owner:

```sh
git clone https://github.com/eyupmiduck/dml_utils.git
cd dml_utils

liquibase \
  --url=jdbc:postgresql://localhost:5432/mydb \
  --username=dml_utils_owner \
  --password=change-me \
  --changelog-file=dml_utils/src/main/resources/db/changelog/db.changelog-master.xml \
  --liquibase-schema-name=liquibase \
  --database-changelog-table-name=dml_utils_databasechangelog \
  --database-changelog-lock-table-name=dml_utils_databasechangeloglock \
  update
```

`liquibase status` reports pending changesets; re-running `update` is safe (Liquibase skips changesets it has already
applied).

### Rolling back

Every changeset ships a rollback. Pass the same connection flags and replace
`update` with `rollback-count --count=<n>` to undo the last `n` changesets:

```sh
liquibase \
  --url=jdbc:postgresql://localhost:5432/mydb \
  --username=dml_utils_owner \
  --password=change-me \
  --changelog-file=dml_utils/src/main/resources/db/changelog/db.changelog-master.xml \
  --liquibase-schema-name=liquibase \
  --database-changelog-table-name=dml_utils_databasechangelog \
  --database-changelog-lock-table-name=dml_utils_databasechangeloglock \
  rollback-count --count=1
```

## Custom PostgreSQL image

The build and local dev database use a custom image (`dml-utils-postgres:<ver>-alpine`) built from the official
`postgres:<ver>-alpine`
image. It bakes in a roles init script (`scripts/postgres/roles.sql`) that
creates the application roles and the `liquibase` schema before Liquibase runs:

- `dml_utils_owner` — owns the schemas and objects; Liquibase connects as this
  role (never as `postgres`).
- `dml_utils_caller` — the role privileges are granted to.
- `dml_utils_test` — granted `dml_utils_caller`; used by the integration tests.

The image also compiles two extensions from source (pinned and
checksum-verified), so they are available in dev databases:

- [`plpgsql_check`](https://github.com/okbob/plpgsql_check) — static analysis
  of the routines.
- [`pg_background`](https://github.com/vibhorkum/pg_background) — runs SQL in
  background workers with autonomous transactions, used by the chunking
  routines.

Both are created by the init script and in every test database (via the
migrated template).

The init script only runs on first initialization, so an existing data volume
keeps its roles and installed extensions as-is; recreate the volume (`scripts/refresh-local-db.sh`) to pick up a new
image.

## Repository layout

```
dml_utils/src/main/resources/db/changelog/   Liquibase changelog (master + changes)
dml_utils/src/test/java/io/github/eyupmiduck/dmlutils/   JUnit tests
scripts/                                     workflow_library submodule: custom image
                                             (postgres/, compose.yaml), local DB,
                                             release helpers, shared .sqlfluff
.env                                         Dev-stack config (HOST_PORT)
.sqlfluff                                    Symlink to scripts/.sqlfluff
```

## Running against a different PostgreSQL version

The PostgreSQL version the build runs against is a single source of truth
controlled by the `postgres.version` Maven property (`17-alpine` by default). It
is used for jOOQ code generation and the integration tests, and it drives the
custom image tag:

```sh
# Build the custom image for that version, then run the build/tests
scripts/build-postgres-image.sh postgres:16-alpine
./mvnw verify -Dpostgres.version=16-alpine

# Local dev database: build the image for that version, then start the stack
POSTGRES_IMAGE=dml-utils-postgres:16-alpine scripts/start-local-db.sh
```

The build rejects a stock `postgres` image at `validate` (it lacks the
application roles and the compiled extensions). CI builds the custom image and
runs the full build against PostgreSQL 16, 17, 18 and 19rc1 (see
`.github/workflows/maven.yml`).

## Changelog validation dependency

Changelog validation (changeset and SQL naming, orphaned SQL files) lives in the
[`liquibase_validation`](https://github.com/eyupmiduck/liquibase_validation)
project and is consumed as the test-scoped `io.github.eyupmiduck:liquibase-validation`
artifact from GitHub Packages. GitHub Packages requires authentication even for
public packages, so a classic personal access token with the `read:packages`
scope must be configured under the `github` server id in `~/.m2/settings.xml`
for local builds. See that project's README for the settings snippet and the CI
access requirements.

## Open source projects

dml_utils is built on and maintained with these open source projects:

- [PostgreSQL](https://www.postgresql.org/) — the database these helpers target
  and exercise.
- [Liquibase](https://www.liquibase.org/) — applies and versions the database
  schema changes.
- [jOOQ](https://www.jooq.org/) — generates the type-safe Java classes used by
  the tests and consumers.
- [Testcontainers](https://testcontainers.com/) — runs the throwaway PostgreSQL
  container for jOOQ code generation and the integration tests.
- [JUnit 5](https://junit.org/) — the test framework.
- [pg_background](https://github.com/vibhorkum/pg_background) — runs each chunk
  in a background worker with an autonomous transaction.
- [plpgsql_check](https://github.com/okbob/plpgsql_check) — statically analyses
  the PL/pgSQL routines.
- [SQLFluff](https://sqlfluff.com/) — lints the changelog SQL files.
- [liquibase-validation](https://github.com/eyupmiduck/liquibase_validation) —
  the changelog linter and the `plpgsql_check`/audit-column test helpers.
- [CodeQL](https://codeql.github.com/) — static analysis of the Java code in CI.
- [Dependabot](https://github.com/dependabot) — keeps the Maven and GitHub
  Actions dependencies up to date.
- [OpenJDK](https://openjdk.org/) — provides the Java runtime (Java 25) the
  project targets.
- [Apache Maven](https://maven.apache.org/) — builds the project and manages
  dependencies (through the Maven Wrapper).
- [OpenCodeReview](https://open-codereview.ai/) — runs the AI code review on
  pull requests.

## More

- [CONTRIBUTING.md](CONTRIBUTING.md) — build, test, and changelog conventions
- [AGENTS.md](AGENTS.md) — repository layout and development principles
- [SECURITY.md](SECURITY.md) — how to report a security issue

## License

See [LICENSE](LICENSE).
