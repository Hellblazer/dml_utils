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
`smallint`, `integer`, `bigint`, `text` or `uuid` (the column names need not be
`id`); the chunks are primary-key ranges, so the planner can use the primary-key
index.

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
  boundary's primary-key value (position-aligned arrays, one per key kind), and
  the fixed-row chunk migration tables
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
- `<chunking_clause>` — replaced by the chunk's primary-key range,
  `((<alias>.<pk1>, ...) >= (<start1>, ...) AND (<alias>.<pk1>, ...) <op>
  (<end1>, ...))`, where every start and end is an explicitly cast literal and
  `<op>` is `<` for every chunk except the last, which uses `<=` so the captured
  maximum row is included. A one-column key degenerates to a scalar comparison (`((<alias>.<pk>) >= (<start>))`).

The driving table must have a **primary key of up to three columns**, each of
type `smallint`, `integer`, `bigint`, `text` or `uuid`; the column names are read
from the catalog, so they need not be `id`. The predicate compares the key tuple
to explicitly cast literals (`(t.id) >= ('1'::bigint)`, or
`(t.a, t.b) >= ('1'::bigint, 'x'::text)` for a composite key) so the planner can
use the primary-key index. Chunks are cut by row number over `ORDER BY <pk>`;
rows inserted later with keys above the captured maximum are outside the final
chunk and are not processed.

The first call for a `label` computes the boundaries (via
`populate_migration_boundaries`) and then processes them. Re-running with the
same `label` is safe: it resumes at the first unprocessed chunk using the SQL,
chunk size and driving table recorded when the run was created (a differing
`i_sql_text`, `i_chunk_size` or `i_driving_table_*` is ignored, with a notice),
and a run that is already complete is a no-op. To change the SQL of an existing run deliberately, use
`dml_utils.set_migration_run_sql_text`. Progress and completion are visible in
`dml_utils_data.migration_run` and `dml_utils_data.migration_boundary`.

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
-- primary-key column, so (boundary_id).bigint_values[1] reads the first column
-- of a bigint key.
SELECT boundary_no, (boundary_id).bigint_values[1], completed_at
FROM dml_utils.migration_boundaries(i_run_id => 42);

-- The errors recorded for the run, so it can be diagnosed without the worker
-- logs.
SELECT boundary_no, sqlstate, message, created_at
FROM dml_utils.migration_errors(i_run_id => 42);
```

To inspect the plan for one real chunk (rather than the synthetic representative
ranges), supply its start and end boundary values — one text value per
primary-key column, in key order — and whether it is the final chunk:

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
at the first changeset that calls a helper. The versioned
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
