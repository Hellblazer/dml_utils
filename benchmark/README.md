# Chunked-migration thread-scaling benchmark

A self-contained, repeatable benchmark of `dml_utils.run_migration_chunks` against
a synthetic table. It runs the migration with 1 thread, then 2, then 3, ... up to
a maximum you choose, several times at each value, and reports how long the
chunk-boundary calculation and the per-chunk workload took on average, for the
`primary_key`, `ordered_key` and `blocks` chunking strategies.

Everything needed to reproduce a run lives in this directory.

## How to run a benchmark, step by step

The worked example below runs **threads 1..20 with 10 measured runs each** on a **1,000,000-row** table. No prior
knowledge of the scripts is assumed.

### Step 0 — prerequisites

- Docker is installed and running (`docker info` works).
- The repository is checked out and its `scripts/` submodule is present (`git submodule update --init` if
  `scripts/start-local-db.sh` is missing).
- The first setup needs internet access: the Liquibase container downloads its
  PostgreSQL JDBC driver. `setup.sh` also builds the custom PostgreSQL image
  automatically if it is not present.

### Step 1 — set the parameters

Open [`config.env`](config.env) in an editor and set the values for the sweep. For
the example, the file should contain these lines (each is `NAME=value`; the file
is sourced by the scripts, so do not `export` them):

```sh
BENCH_ROWS=1000000
BENCH_PK_COLUMNS=1
BENCH_CHUNK_SIZE=1000
BENCH_CHUNK_BY=primary_key
BENCH_THREADS_FROM=1
BENCH_THREADS_TO=20
BENCH_RUNS=10
BENCH_WARMUP_RUNS=1
```

Set `BENCH_CHUNK_BY=blocks` to benchmark the physical block (ctid) strategy
instead, and `BENCH_PK_COLUMNS` to `2` or `3` for a two- or three-column bigint
primary key (see [Parameters](#parameters)). Set `BENCH_CHUNK_BY=ordered_key` to
benchmark a UUIDv4 primary key with a `(created_at, id)` unique index on
`bench_ordered_source`; `BENCH_PK_COLUMNS` does not apply to it, and
`BENCH_CHUNK_SIZE` is in rows.

These are all the defaults, so the example works out of the box. Edit the file
rather than overriding the environment: each script sources `config.env`, so a
value exported for only one of them is not seen by the others, and `report.sh`
would then describe the wrong settings.

To keep personal settings out of git, copy
[`config.local.env.example`](config.local.env.example) to `config.local.env`
(gitignored) and set only the values you want to change there; it is sourced after
`config.env`, so it overrides the defaults.

For a one-off override without editing either file, export it in your shell so
every script sees it:

```sh
export BENCH_ROWS=100000000
benchmark/setup.sh
benchmark/run.sh
benchmark/report.sh
```

A `BENCH_ROWS=100000000 benchmark/setup.sh` prefix also works, but then repeat it
for `run.sh` and `report.sh`, or they fall back to the file's default.

### Step 2 — create the benchmark database (once)

From the repository root:

```sh
benchmark/setup.sh
```

This starts a dedicated PostgreSQL container, applies the `ddl_utils` changelog
and then the `dml_utils` changelog to it, and loads the deterministic
1,000,000-row source table. It is safe to
re-run. If you later change a **server setting** (for example
`BENCH_MAX_WORKER_PROCESSES`) or the **dataset**, recreate the container and
data with:

```sh
benchmark/setup.sh --refresh
```

### Step 3 — run the sweep

```sh
benchmark/run.sh --reset
```

- `--reset` starts a fresh results file. Omit it to add more runs to the
  existing file (the means then cover every recorded run).
- It prints one line per measured run as it finishes, and one per warm-up.
- This example runs `1..20` threads × (`1` warm-up + `10` measured) = **220
  runs**, each processing the whole table, so it can take a while (tens of
  minutes to a few hours depending on the machine). You can stop it with
  `Ctrl-C` at any time; the results already written are kept.
- To resume after a failure (or to add runs for only some thread values), set
  `BENCH_THREADS_FROM`/`BENCH_THREADS_TO` to the range you still need and run
  without `--reset`; the mean then covers every run recorded for that thread
  value. Use `--reset` instead to discard everything and start clean.

### Step 4 — format the results

```sh
benchmark/report.sh
```

This writes the findings page to `results/lhorner_laptop_oct_2026/3_col_pk/report.md` and prints its
path. Re-run it any time to refresh the means after more runs.

### Step 5 — read the results

Open `results/lhorner_laptop_oct_2026/3_col_pk/report.md`. It contains:

- the **configuration** that produced the results and a **dataset** section (live
  source rows and blocks),
- the **hardware and PostgreSQL settings** captured on this machine,
- a **results table per strategy, key and chunk size**, one row per thread value,
  with the number of chunks, the `rows/chunk` and `blocks/chunk` averages, the mean
  range-calculation time, the mean chunk-run time, the mean total, and the speedup
  relative to the lowest thread value,
- a **chart per strategy, key and chunk size**
  (`report-<strategy>-<chunk_size>-<pk_columns>.svg`, generated next to the page)
  of the mean total time against the thread count, embedded in the page.

The raw per-run data is `results/lhorner_laptop_oct_2026/3_col_pk/benchmark.csv` (one row per measured
run, tagged with the strategy and chunk unit); `results/lhorner_laptop_oct_2026/3_col_pk/hardware.txt`,
`results/lhorner_laptop_oct_2026/3_col_pk/postgres-settings.txt` and
`results/lhorner_laptop_oct_2026/3_col_pk/dataset.txt` hold
the captured environment and dataset.

## What is measured

Each run calls `run_migration_chunks` once and reads the run's timestamps back:

| column in the CSV | meaning                                                                            |
|-------------------|------------------------------------------------------------------------------------|
| `strategy`        | the chunking strategy (`primary_key`, `ordered_key` or `blocks`)                   |
| `chunk_unit`      | the chunk-size unit: `rows` for `primary_key`/`ordered_key`, `blocks` for `blocks` |
| `chunk_size`      | the configured chunk size, in `chunk_unit`                                         |
| `threads`         | the thread count                                                                   |
| `run`             | the measured run number                                                            |
| `chunks`          | the number of chunks the run processed                                             |
| `range_seconds`   | `boundaries_calculated_at - started_at` — the boundary calculation                 |
| `chunk_seconds`   | `completed_at - boundaries_calculated_at` — the workload over all chunks           |
| `total_seconds`   | `completed_at - started_at`                                                        |
| `pk_columns`      | the key columns, joined by `+` (`k1+k2`, or `created_at+id` for `ordered_key`)     |

Warm-up runs are not recorded. `report.sh` groups the rows by `(strategy,
chunk_size, pk_columns)` and averages the timing columns per group and thread
value, so runs with different chunk sizes or key shapes are never combined.

## Parameters

All parameters live in [`config.env`](config.env) and are documented there; put
personal overrides in a gitignored `config.local.env` (copy
`config.local.env.example`). The ones you are most likely to change:

| parameter                                 | default                           | purpose                                                     |
|-------------------------------------------|-----------------------------------|-------------------------------------------------------------|
| `BENCH_ROWS`                              | `1000000`                         | synthetic source rows                                       |
| `BENCH_PK_COLUMNS`                        | `1`                               | number of bigint primary-key columns (1 to 3)               |
| `BENCH_ORDERED_SOURCE_TABLE`              | `bench_ordered_source`            | UUIDv4-keyed ordered-key source                             |
| `BENCH_ORDERED_TARGET_TABLE`              | `bench_ordered_target`            | throwaway ordered-key destination                           |
| `BENCH_ORDERED_INDEX`                     | `bench_ordered_created_at_id_idx` | the `(created_at, id)` unique index                         |
| `BENCH_CHUNK_SIZE`                        | `1000`                            | chunk size, in the unit of `BENCH_CHUNK_BY`                 |
| `BENCH_CHUNK_BY`                          | `primary_key`                     | chunking strategy: `primary_key`, `ordered_key` or `blocks` |
| `BENCH_THREADS_FROM` / `BENCH_THREADS_TO` | `1` / `20`                        | thread sweep                                                |
| `BENCH_RUNS`                              | `10`                              | measured runs per thread value (the mean is over these)     |
| `BENCH_WARMUP_RUNS`                       | `1`                               | unrecorded warm-up runs per thread value                    |
| `BENCH_SQL`                               | INSERT into the target            | the workload run once per chunk                             |
| `BENCH_ORDERED_SQL`                       | INSERT into the ordered target    | the ordered-key workload                                    |
| `BENCH_MAX_WORKER_PROCESSES`              | `30`                              | must cover `BENCH_THREADS_TO`                               |
| `BENCH_SHARED_BUFFERS`                    | `512MB`                           | server setting for the run                                  |

`BENCH_SQL` must contain `<driving_table>` and `<chunking_clause>` exactly once;
the driving table is aliased `t`. The default keeps the source table untouched by
copying each chunk into `benchmark.bench_target`, which is truncated before every
run. The same contract applies to `BENCH_ORDERED_SQL`, which copies the UUID key,
`created_at` and payload into `bench_ordered_target` for `ordered_key`.

`BENCH_CHUNK_BY` selects the strategy: `primary_key` (the default) chunks a fixed
number of rows in primary-key order; `ordered_key` chunks a fixed number of rows
in the order of `BENCH_ORDERED_INDEX`, a `(created_at, id)` unique index on
`bench_ordered_source`; `blocks` chunks a fixed number of physical
heap blocks (`BENCH_CHUNK_SIZE` blocks) and requires a quiescent, read-only source,
which the default INSERT-into-target workload provides. `primary_key` and
`blocks` are not directly comparable at the same numeric chunk size: a block holds
a variable number of rows. `primary_key` and `ordered_key` both chunk fixed rows,
so compare those two by `rows/chunk`; each uses its own source/target shape.
`run.sh` records the strategy and the chunk unit in each CSV row, and `report.sh`
reports them separately (one table and chart per strategy).

## The benchmark database

The benchmark uses its **own** PostgreSQL container, not the shared dev stack, so
the server settings a reproducible sweep needs can be set without touching the dev
database:

- **Image**: `dml-utils-postgres:17-alpine` (the custom image with the
  `dml_utils_owner` / `dml_utils_caller` roles, `plpgsql_check` and
  `pg_background`).
- **Container / volume / network / port**: `BENCH_CONTAINER`, `BENCH_VOLUME`,
  `BENCH_NETWORK`, `BENCH_HOST_PORT` in `config.env`.
- **`max_worker_processes`**: set to `BENCH_MAX_WORKER_PROCESSES` (default 30).
  `run_migration_chunks` refuses a thread count above `max_worker_processes`, so
  this must be at least `BENCH_THREADS_TO` plus headroom for autovacuum and other
  workers. Changing it needs `setup.sh --refresh`.
- **`pg_background.max_workers`**: pg_background independently caps concurrent
  workers per session (default 16). `run.sh` raises it to the run's thread count
  in the session that calls `run_migration_chunks`, so a sweep past 16 threads
  needs no server change.
- **Schema**: the changelog is applied to the container's `dml_utils` database by
  the Liquibase image, so the routines under test are exactly the repository's.
- **Data**: `sql/create-source.sql` creates `benchmark.bench_source` with
  `BENCH_PK_COLUMNS` bigint key columns (`k1..kN`) and deterministic key tuples
  and payloads, so refreshing the database reproduces the table exactly.
  `sql/create-ordered-source.sql` creates `benchmark.bench_ordered_source` with
  deterministic UUIDv4-format primary keys and a `(created_at, id)` unique index;
  ten consecutive rows share a timestamp, so `id` is the tiebreaker. Their
  throwaway destinations are `benchmark.bench_target` and
  `benchmark.bench_ordered_target`.

All database access goes through `docker exec`, so no host `psql` is required;
use `benchmark/psql.sh` for an interactive session. To remove the container and
reclaim its disk space, run `benchmark/teardown.sh` (the host results are kept;
recreate with `benchmark/setup.sh`).

## Smoke test

`benchmark/smoke.sh` runs a small end-to-end check without touching the
configured benchmark database. It creates an isolated container, volume and
network, loads 2,000 rows, runs one measured run per chunking strategy (`primary_key`, `blocks` and `ordered_key`) with
two threads,
and tears everything down again.
Pass `--keep` to leave the isolated database running (published on port 55434)
for inspection.

The script ignores `config.local.env` (`BENCH_IGNORE_LOCAL_CONFIG=1`) and uses
its own container names, so it is safe to run next to a configured sweep. CI
runs the same script from `.github/workflows/benchmark-smoke.yml` when the
benchmark, a changelog or the build files change; run it locally after changing
a routine or the harness.

## Files

```
benchmark/
  config.env              every parameter (edit this to change the sweep)
  config.local.env.example  template for gitignored personal overrides
  setup.sh                create/refresh the dedicated benchmark database
  run.sh                  run the sweep, appending results/<BENCH_CSV>
  report.sh               format the results into results/<BENCH_REPORT>
  chart.awk               render the mean total time as the report's SVG chart
  psql.sh                 open a psql session on the benchmark container
  smoke.sh                small isolated end-to-end check, used by CI
  teardown.sh             remove the container, data volume and network
  sql/create-source.sql   deterministic synthetic source table + data
  sql/create-target.sql   throwaway destination the workload inserts into
  sql/create-ordered-source.sql  UUIDv4 source with a (created_at, id) index
  sql/create-ordered-target.sql  throwaway ordered-key destination
  results/                generated: benchmark.csv, report.md,
                          report-<strategy>-<chunk_size>-<pk_columns>.svg, dataset.txt, ...
```

## Caveats

- The data is synthetic; absolute times are not production numbers. Use it to
  compare thread values on the same machine and dataset.
- The default workload is an `INSERT ... SELECT` from the source into a
  throwaway target, so the source is read-only and each run starts clean. Point
  `BENCH_SQL` at a different statement to benchmark something else, and note that
  `run_migration_chunks` assumes idempotent chunk SQL when resuming.
- The `blocks` strategy requires a quiescent, read-only, plain-heap source (see
  the block-chunking limitations in the routines README). The default
  INSERT-into-target workload satisfies this; a `BENCH_SQL` that updates the
  source does not.
- `ordered_key` uses a separate UUIDv4 source and target, so its absolute numbers
  include that table's shape; compare it with `primary_key` by `rows/chunk`
  rather than by raw chunk size. It needs the `(created_at, id)` index to stay
  valid for the whole run; the harness recreates it in `setup.sh`.
- Autovacuum, checkpoints and other background activity add noise. Increase
  `BENCH_RUNS` to tighten the means; warm-ups are excluded.
- Each thread is one `pg_background` worker, capped by `max_worker_processes`.
