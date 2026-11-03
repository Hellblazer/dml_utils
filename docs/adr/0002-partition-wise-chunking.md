# ADR 0002: Partition-wise chunking for declarative partitioned tables

- **Status:** Accepted
- **Date:** 2026-11-02
- **Beads:** `dml-sw6` (epic), `dml-sw6.1` (this design)

## Context

`run_migration_chunks` currently chunks one relation. For a declarative
partitioned parent it either scans the parent (chunks span leaves, the plan scans
every partition and locks are broad) or, for `blocks`, rejects the table because
the parent has no single heap.

The goal is a **partition-wise** mode that enumerates the leaves, applies the
existing range or ctid strategy inside each leaf, and flattens the per-leaf
boundary sequences into the runner's contiguous `0..N` order. Each worker then
touches one leaf, so the plan prunes to that leaf and locks/indexes are
partition-scoped.

The mode must also be **resumable and recoverable at every step**, including
while the per-leaf boundary ranges are being calculated. A failure after some
leaves have been calculated must leave those leaves committed and let a retry
continue with the first pending or failed leaf; it must never process a partial
boundary sequence.

## Decision

### 1. Partition-wise is a modifier, not a new strategy value

Add a boolean option rather than new `chunking_strategy` values:

```sql
run_migration_chunks(
    ...,
    i_chunk_by           => 'primary_key',  -- or ordered_key or blocks
    i_partition_wise     => true,
    i_boundary_key_index => ...            -- ordered_key only
)
```

`chunk_by` continues to mean the **inner** strategy applied inside each leaf.
`partition_wise` means "run that strategy once per leaf". The run stores
`partition_wise` so resume and explain use the stored mode.

This avoids a combinatorial enum (`partition_wise_primary_key`,
`partition_wise_blocks`, ...), keeps the existing strategies and renderers
intact, and lets validation say "partition-wise plus the chosen inner strategy".

`partition_wise` requires a declarative partitioned parent (`relkind = 'p'`). For `blocks`, every leaf must be a plain
heap (`relkind = 'r'`). For key strategies, the key is resolved once from the parent
and applied to each leaf's key columns.

### 2. Leaves and identity

Leaves are the actual storage relations below the parent, found with
`pg_partition_tree` and filtered to `relkind = 'r'`. A partitioned sub-parent is
expanded to its leaves; a foreign or otherwise non-plain leaf is rejected.

The run stores a **leaf manifest**: one row per leaf, in deterministic order.
The manifest row stores:

- `run_id` and `leaf_ordinal` (1..N, deterministic);
- `leaf_schema_name`, `leaf_name` and `leaf_oid`;
- `leaf_bound` (the leaf's `pg_get_expr(relpartbound, oid)`, so a changed bound
  is detectable);
- `relation_filepath` for a `blocks` run (per-leaf heap identity);
- `state` (`pending`, `calculating`, `calculated`, `failed`);
- `boundary_count`, `terminal_boundary_id` (the leaf's high-water key captured
  when its starts were calculated), `calculated_at`, and error fields.

The leaf list is snapshotted into the manifest **before** any leaf boundaries
are calculated. Later enumerations are compared with the snapshot for
changed-tree detection; they are never silently substituted.

The deterministic order is the contract for numbering and resume. The preferred
order is `(leaf_bound, leaf_schema_name, leaf_name, leaf_oid)` with a
byte-order (`COLLATE "C"`) text comparison, so every run sees the same sequence.
The exact ordering is specified and tested in `dml-sw6.3`.

A boundary stores `leaf_ordinal`, which resolves through the manifest. The
terminal high-water boundary references the last non-empty leaf and remains a
terminal marker, not a chunk.

### 3. Schema

Edit the pre-deployment baseline in place:

- `migration_run.partition_wise boolean NOT NULL DEFAULT false`.
- `migration_run_leaf` (the manifest) with the fields above, keyed by
  `(run_id, leaf_ordinal)` and `UNIQUE (run_id, leaf_oid)`, cascading from the
  run. A calculated leaf must have `calculated_at`; a failed leaf carries an
  error SQLSTATE/message. An empty leaf is a calculated row with
  `boundary_count = 0`, never an absent row.
- `migration_boundary.leaf_ordinal integer`, with a composite foreign key
  `(run_id, leaf_ordinal)`. A partition-wise boundary must have a leaf; a
  non-partition-wise boundary must not. This is enforced by the boundary
  trigger (or an equivalent check), because the rule depends on the run.
- The run's `driving_table_relation_filepath` remains for a non-partition-wise
  `blocks` run only. A partition-wise `blocks` run has a NULL run filepath and
  one filepath per manifest row. The existing
  `migration_run_chunk_by_filepath_check` is edited accordingly.
- `boundaries_calculated_at` stays the finalization marker. While it is NULL,
  the run is not processable, even if some boundaries exist.
- `total_chunks` stays 0 until finalization. `total_chunks_completed` is
  processing state and is never advanced during calculation.

### 4. Calculation protocol (resumable)

Boundary calculation is split into three independently committed phases. A
function cannot commit, so the coordinator launches short `pg_background`
workers; each worker commits its own transaction.

1. **Create the run and manifest.** One owner-owned worker validates the parent,
   every leaf, privileges and the key, enumerates the leaves, and inserts the
   run (`partition_wise = true`, `boundaries_calculated_at IS NULL`,
   `total_chunks = 0`) plus one `pending` manifest row per leaf. It commits.
   If validation fails, no run or manifest row is written.
2. **Calculate one leaf per transaction, in ordinal order.** A worker claims the
   first `pending` or `failed` leaf, re-checks that the leaf still matches the
   manifest (identity, bound, and filepath for `blocks`), computes that leaf's
   chunk **starts** (not a per-leaf terminal), inserts them at
   `max(boundary_no) + 1`, snapshots the leaf's high-water key on the manifest,
   marks the leaf `calculated` with its boundary count, and commits. It stops at the first failure; earlier leaves stay
   committed.
3. **Finalize.** After every leaf is `calculated`, one worker inserts the single
   terminal high-water boundary from the last non-empty leaf, sets
   `boundaries_calculated_at` and `total_chunks`, and commits. Finalization is
   idempotent and does nothing if the run is already finalized. If every leaf
   is empty, there is no terminal boundary and `total_chunks = 0`.

The leaf transaction performs the claim, the boundary insert and the state
update together, so a crash cannot leave a calculated leaf without its
boundaries or boundaries without a completed state.

A failed leaf's transaction rolls back only that leaf. The coordinator records
the failure on the manifest in a separate short transaction (`state = 'failed'`,
error fields) and stops; a later call retries the first failed leaf. A crashed
coordinator leaves the leaf `pending`; a `calculating` row from a crash is also
treated as retryable (the claim and the calculation share one transaction, so
normally it cannot persist). A `calculated` leaf is never recalculated.

A chunk at the end of a leaf is bounded by that leaf's stored high-water
snapshot (inclusive), not by the next leaf's first key: manifest order need not
match key order, and a later delete must not regress the bound. The stored
snapshot is the calculation-time maximum, so rows inserted after calculation stay
outside the run, matching the single-relation terminal semantics.

The per-leaf boundary numbering is append-only and sequential, so the sequence
stays contiguous across commits. Parallel **processing** is unchanged; parallel **calculation** is deliberately not used
in v1.

### 5. Resume and the processing gate

`resolve_migration_run` and `populate_migration_boundaries` must distinguish
three states:

- no run: create the run + manifest and calculate;
- run with `boundaries_calculated_at IS NULL`: resume calculation at the first
  `pending`/`failed` leaf (finalize if all leaves are `calculated`); return the
  stored inputs only after finalization;
- run with `boundaries_calculated_at IS NOT NULL`: process normally.

`run_migration_chunks` must never enter the chunk-processing loop while
`boundaries_calculated_at IS NULL`, and `complete_migration_run` must not mark
such a run complete. Re-running the same label is the resume operation; the
caller does not archive a run that is still calculating.

A resumed run keeps the stored `partition_wise` flag and inner `chunk_by`; a
differing input is ignored with a notice, as for the other stored inputs.

### 6. Changed partition tree: fail closed

Before each leaf calculation and before processing, compare the live tree with
the manifest:

- the leaf set and ordinals;
- each leaf's schema, name, OID and partition-bound expression;
- each leaf's `relation_filepath` for `blocks`.

On any difference, raise `55000` and leave the existing progress committed. Do
not attempt to reconcile a partially calculated run: a detach, attach, boundary
change, leaf drop/recreate or rename makes the stored ranges stale, and mixing
old and new leaves would silently skip or duplicate rows. The caller archives
the run and starts a new one. A changed tree after calculation but before
processing is rejected the same way.

### 7. Concurrency and cancellation

Calculation is serialized with a run-level advisory lock keyed by `run_id`
(session-level, released on success, error or session exit). A second caller
that cannot acquire the lock raises `55000` ("boundary calculation already in
progress") rather than appending duplicate boundaries or waiting indefinitely.

The coordinator checks the run's `cancelled_at` before creating a leaf worker
and before processing. Cancellation stops launching new leaves; already
committed leaves remain, the remaining leaves stay pending, and the run is
terminal once cancelled (a cancelled run is not resumed; archive it). The chunk
processing path keeps its existing claim/cancellation behaviour.

### 8. API and explain

- `run_migration_chunks`, `populate_migration_boundaries` and
  `resolve_migration_run` gain trailing
  `i_partition_wise boolean DEFAULT false`.
- `i_boundary_key_index` remains the parent unique index for `ordered_key`; for
  partition-wise ordered keys the parent index is resolved to the key
  descriptor and every leaf's matching child index is validated before the run.
- `explain_migration_chunks` and `explain_migration_chunk` gain
  `i_partition_wise` and a leaf selector (ordinal or name; the first leaf is
  the default). Their plan rows identify the leaf schema/name. The run-based
  explain reads the boundary's `leaf_ordinal`; the synthetic explain renders the
  selected leaf.
- `run_function_over_table` stays non-partition-wise in v1: its contract binds
  the function's argument types to one key and its template is generated from
  the parent. Supporting it is a follow-up, not part of this decision.
- Non-partitioned runs keep their current API and behaviour; the default is
  `partition_wise = false`.

### 9. Privileges and parent/leaf semantics

The caller's privilege on the parent does not imply a privilege on each leaf
when the runner addresses the leaf directly. Validate per leaf:

- the owner running the boundary calculation needs `SELECT` on the leaf;
- the invoker running the chunk SQL needs whatever the template needs on the
  leaf (typically `UPDATE`).

A missing leaf privilege fails before the run is created. If a leaf loses a
privilege after creation, the calculation or processing fails with a clear
privilege SQLSTATE and earlier progress stays committed; the operator fixes the
grant and retries. Leaves are addressed as `schema.leaf` (no `ONLY`); a foreign
leaf is rejected.

### 10. Baseline policy

Nothing is deployed. Edit `006-create-migration-tables.sql`, the create/type
scripts, routine files, grants and rollbacks in place; no compatibility
changesets or backfills. Recreate local/dev/benchmark databases.
`LiquibaseUpgradeTest` remains suspended (`dml-b87.12`); restoration (`dml-b87.13`) is gated on this epic plus the
ordered-key work.

## Example

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
    i_sql_text                  => 'UPDATE <driving_table> SET payload = ... WHERE <chunking_clause>',
    i_driving_table_schema_name => 'app',
    i_driving_table_name        => 'events',
    i_label                     => 'events-partition-backfill',
    i_chunk_size                => 10000,
    i_threads                   => 4,
    i_chunk_by                  => 'primary_key',
    i_partition_wise            => true
);
```

If the boundary calculation fails on `events_2026_02`, `events_2026_01` keeps
its committed boundaries and the run stays active with
`boundaries_calculated_at IS NULL`. Calling the same label again resumes at
`events_2026_02`, finalizes, and then processes chunks. Attaching or detaching
a partition in between fails with `55000`; archive the run and start a new one.

## Alternatives considered

- **New `chunking_strategy` values per inner strategy.** Rejected: combinatorial
  enum, duplicated validation/rendering and a misleading `chunk_by`.
- **Calculate all leaves in one transaction.** Rejected: not resumable; a
  failure loses every leaf. It is today's safe-but-atomic behaviour.
- **Infer progress from the boundary rows.** Rejected: an empty leaf has no
  boundaries, so absence cannot distinguish "not calculated" from "calculated
  empty".
- **Per-leaf local boundary numbers and a final renumber.** Rejected in v1:
  sequential append is simpler and keeps the runner's contiguous 0..N
  invariant without a rewrite pass. Calculation is serialized.
- **Reconcile a changed tree automatically.** Rejected: stale ranges can skip or
  duplicate rows; fail closed is deterministic and matches the ordered-key
  resume policy.
- **Store only schema/name.** Rejected: names change on rename and OIDs change
  on drop/recreate. Store both and compare both, alongside the bound
  expression.
- **A procedure with `COMMIT` between leaves.** Rejected for v1: worker
  orchestration matches the existing autonomous `pg_background` architecture and
  avoids a long-lived procedure transaction. A procedure remains a possible
  implementation detail if the coordinator becomes simpler.

## Consequences

Positive:

- A failed leaf calculation no longer discards completed leaves; resume is
  bounded by the first pending/failed leaf.
- Per-leaf boundaries survive a coordinator crash; finalization is separate and
  idempotent.
- A changed partition tree cannot silently mix leaves; the run fails closed.
- The inner strategy remains one of the existing, tested strategies.

Negative / risks:

- More state (manifest), more transactions and an explicit resume gate.
- Boundary calculation is serialized; only chunk processing is parallel.
- The API and explain path gain a modifier and a leaf identity.
- Per-leaf privileges and empty-leaf handling must be exact.
- A changed tree requires a new run (an intentional trade-off for safety).

## Non-goals

- Old-style inheritance trees, foreign partitions, moving rows between
  partitions, automatic partition creation/rotation.
- Cross-partition chunks.
- Parallel boundary calculation in v1.
- Reconciling a changed partition tree.
- Partition-wise `run_function_over_table` in v1.
- Old runs or backfills; nothing is deployed.

## Implementation map

| Bead         | Work                                                                               |
|--------------|------------------------------------------------------------------------------------|
| `dml-sw6.1`  | This design                                                                        |
| `dml-sw6.2`  | `partition_wise`, leaf manifest, boundary leaf identity, constraints, grants, jOOQ |
| `dml-sw6.3`  | Enumerate and snapshot leaves deterministically                                    |
| `dml-sw6.4`  | Per-leaf boundary builder and coordinator/finalizer                                |
| `dml-sw6.5`  | Render and explain per leaf                                                        |
| `dml-sw6.6`  | Runner resume gate, calculation lock, cancellation                                 |
| `dml-sw6.7`  | Eligibility, per-leaf privileges and invalid-leaf handling                         |
| `dml-sw6.8`  | Integration coverage, including mid-calculation failure and attach/detach          |
| `dml-sw6.9`  | Documentation                                                                      |
| `dml-sw6.10` | Partitioned benchmark variant                                                      |
| `dml-b87.13` | Restore the previous-release upgrade test after this epic                          |
