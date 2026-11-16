# ADR 0004: A committing entry point, so the coordinator does not pin the vacuum horizon

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

`run_migration_chunks` is a function, so one `SELECT` runs a whole migration as
one SQL statement. Each chunk runs in its own `pg_background` worker and commits
there, but the calling session stays inside that one statement, and the
statement's snapshot holds the vacuum horizon until the call returns. `VACUUM`
removes a dead row only when no snapshot can still see it, so nothing the
committed chunks left behind can be reclaimed while the run goes on, and the
driving table bloats by the whole migration rather than by one chunk.

This was measured, not assumed. On PostgreSQL 17.5 with autovacuum off, a move
over a 20,004-row table in 21 chunks of 1,000 (threads 1, 150 ms between
chunks): the coordinator showed `backend_xmin = 1692` while the current
transaction id was 1704, and a `VACUUM (VERBOSE)` seven chunks in printed
`tuples: 0 removed, 27004 remain, 7000 are dead but not yet removable; removable
cutoff: 1692`, the coordinator's xmin. After the call returned the same `VACUUM`
removed all 20,004. A PL/pgSQL procedure that launched chunks, `COMMIT`ted after
each launch and waited between them held no `backend_xmin` between chunks, and a
mid-run `VACUUM` left nothing not yet removable.

The README had said the opposite: that each worker being its own backend keeps
bloat bounded by a single chunk, and that a procedure that commits between
chunks would bloat the table badly. The first holds only for a coordinator that
holds no snapshot; the second was a claim about running the chunk SQL inside the
procedure, which this library does not do.

## Decision

### 1. A second entry point, a procedure

`dml_utils.run_migration_chunks_committing` takes the same parameters and
defaults as `run_migration_chunks` and has the same semantics (labels, resume,
threads, sleep, partition-wise, ordered key, error recording, cancellation). It
`COMMIT`s after every launch, wait and settle step, so the coordinator never holds
one snapshot across chunks. A function cannot `COMMIT`, so the entry point has to be
a procedure, and a new routine, not a change to the function: `run_migration_chunks`
keeps its signature, its function kind and its place inside the caller's
transaction.

### 2. One coordinator, shared

The coordinator moves out of `run_migration_chunks` into
`dml_utils_lib.coordinate_migration_chunks`, a procedure with one added
parameter, `i_commit`. The function is now a call to it with `i_commit = false`
(a function may `CALL` a procedure as long as the `COMMIT` statement never runs),
and `run_migration_chunks_committing` calls it with `i_commit = true`. There is
one copy of the launch, wait, outcome, throttle, cancellation and failure logic.

PostgreSQL does not allow `COMMIT` inside a block that has an `EXCEPTION` clause,
and the coordinator's clause is what drains the in-flight workers on an
unexpected error. The straight-line loop therefore became a small state machine:
each pass of one outer loop runs one step (`launch`, `wait`, `settle`, `sleep`,
`cancel`, `fail`, `finish`) inside the block with the clause, and the `COMMIT`
sits after the block. The step bodies are the old code unchanged; only their
sequencing and the `EXIT`/`CONTINUE` jumps became phase assignments. The per-chunk
delay uses the same helper as before: the function sleeps the whole delay in one
call, the procedure sleeps it in 100 ms slices, one per pass (the helper's loop body,
inlined: a call to the helper would hold one snapshot for the whole slice), so a
`COMMIT` falls between slices.

### 3. A procedure that commits must be called at top level

Inside an explicit transaction block, from a function, or through a client that
wraps the call in a transaction, the first `COMMIT` fails with `2D000`. The
procedure issues a `COMMIT` as its first statement so that failure comes before
any run exists and a misplaced call changes nothing. The README and the
`COMMENT ON PROCEDURE` state the limit. `run_function_over_table` is a function
and gets no committing variant.

### 4. Changesets and grants

One changeset per routine, `runOnChange`, each with its rollback: the new lib
procedure, the new entry point, and the edited function. The lib procedure is
declared before the function that calls it. Both procedures are revoked from
`PUBLIC` and granted to `dml_utils_caller` in `999-grant-privileges.sql`, with
the matching revokes in its rollback. The existing contracts already cover
procedures: the plpgsql_check run selects `prokind IN ('f', 'p')`, the rollback
contract accepts `DROP PROCEDURE`, and the grant contract checks every procedure
in the application schemas.

## Consequences

- A caller who can `CALL` at top level uses
  `run_migration_chunks_committing` and gets a table that `VACUUM` can keep up
  with during the run; a caller inside a transaction keeps `run_migration_chunks`
  and its pinned horizon.
- `run_migration_chunks` is now a one-statement wrapper. Its observable behavior,
  signature, notices and errors are unchanged, and its own tests are the
  regression suite for the extraction.
- The coordinator's brief per-chunk transaction churn (one empty `COMMIT` per
  step, so several per chunk) is the cost of the committing mode; the function
  path does none of it.
- The coordinator still holds a snapshot while a statement runs: one wait for a
  worker, or one 100 ms slice of the delay. In `RunMigrationChunksBloatTest` its
  `backend_xmin` was non-null in nearly every sample but took 13 distinct values
  over a 12-chunk run (the function's took one), and a `VACUUM` five chunks in
  removed 4,492 rows and left 0 not yet removable, where the function's `VACUUM`
  removed 0 and left 5,000. A snapshot taken after every earlier chunk committed
  never pins a committed chunk.

## Alternatives considered

- **Drive the loop from the engine in short transactions** (the caller issues one
  call per chunk). It avoids the procedure and its top-level restriction, but
  moves scheduling, resume and cancellation out of the library.
- **Change `run_migration_chunks` into a procedure.** It breaks every caller that
  uses it as a function (`SELECT`, jOOQ routine wrappers, `run_function_over_table`).
- **Copy the coordinator into the procedure.** It forks about three hundred lines
  of scheduling and failure handling that must stay identical.
