# ADR 0003: bytea as a key kind

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

A table keyed by a content hash, such as a chunk table with a primary key of
`(tenant_id text, collection text, chash bytea)`, could not be chunked: the
boundary key supported `bigint`, `text`, `uuid` and `timestamptz` only (ADR
0001). The same key must work for `primary_key` and `ordered_key` runs, alone or
as the last column of a composite key, on a plain table and on a partitioned
parent (ADR 0002).

Two properties of `bytea` matter. Its text form depends on the session:
`bytea_output` chooses hex or escape output, and a quoted `'\x...'` literal
depends on `standard_conforming_strings`. And it orders by unsigned bytes, a
shorter key before any longer key it prefixes, which is also the order of its
btree, so boundaries cut by row number over `ORDER BY` the key and the range
predicate agree.

## Decision

### 1. A fifth key kind, `bytea`

`dml_utils_data.migration_key` gains `bytea_values bytea[]` after the four
existing arrays, so every boundary and leaf terminal already stored stays
readable: a row written before the change simply has no bytea array. The kind
is accepted by the primary-key and ordered-key resolvers, the boundary packers
and the renderer. `migration_key_is_canonical` extends its arity, shape and
"exactly one array element per index" rules to the fifth array, and the
`migration_run` ordered-key shape check admits `'bytea'`.

### 2. Hex boundary text, rendered through `decode`

The text form of a bytea boundary value is its bare hex digits, with no `\x`
prefix, produced by `encode(value, 'hex')`. The chunk literal is
`pg_catalog.decode('<hex>', 'hex')`, with the hex normalized to lower case.
Neither depends on `bytea_output` or `standard_conforming_strings`, and
`decode` is immutable, so the planner folds it to a constant and the key index
serves the predicate. A value that is not hex digits, or has an odd number of
them, is rejected with `22023`, like any other invalid boundary value. The empty
key is a valid, non-NULL value.

### 3. New changesets, not an in-place edit of the baseline

ADR 0001 and ADR 0002 edited the baseline in place while it was unpublished and
record that this stopped once it was published. The baseline is published, so
the change is made in new changesets, and `LiquibaseUpgradeTest` stays enabled:

- `008-add-bytea-key-kind` adds the attribute. It is listed between 004 and 005,
  not at the end, because the boundary-key check helper in 005 is a
  `runOnChange` changeset that reads the new attribute, so a database built from
  scratch needs it first. A database upgraded from an earlier release runs 008
  on its own and then re-applies 005.
- `009-allow-bytea-ordered-key-kind` replaces the ordered-key shape check through
  the ddl_utils helpers, adding the new check `NOT VALID`; `010` validates it
  with `ensure_check_constraint`, outside a transaction because the procedure
  commits.
- The routines are edited in place under `runOnChange`. `terminal_leaf_for_run`
  gains an OUT column, so it is dropped and recreated with its grants
  re-asserted.

### 4. The first ddl_utils caller, and its precondition

009 and 010 are the first changesets to call ddl_utils, which the README has
always said dml_utils assumes is installed. 008 commits on its own, so a
database where 009 then failed would keep the fifth attribute while the routines
that build a key still construct the old, narrower row, and every new backend,
pg_background workers included, would fail with `Input has too few columns`.
008 therefore carries a precondition, `onFail="HALT"`, that requires the
`ddl_utils` schema, `USAGE` on it and `EXECUTE` on `drop_constraint`,
`add_check_constraint` and `ensure_check_constraint` for the migrating role. A
database that lacks them halts before anything changes and the migration can be
re-run. The precondition is the one place SQL is written in the changelog XML,
because Liquibase has no file form for it.

No run may be active during the upgrade: between 008 and the changesets that
replace the routines, a worker that starts builds the old key and fails.

### 5. Rollback

Rolling back 008 drops the attribute and with it every stored bytea array, so
runs over a bytea key are archived first. Rolling back 009 restores the old
check `NOT VALID`, because a rollback cannot run the validating procedure; a
remaining run whose ordered key includes bytea then fails every update with
`23514`. The rollback files say so.

## Consequences

- A primary key or ordered key of one to three columns may include a bytea
  column. `numeric` and other types stay unsupported.
- `MigrationKeyRecord`, the generated jOOQ record of `migration_key`, gains a
  fifth constructor argument. This is visible to Java callers that construct it.
- The partition-wise path needed no change: the key is read from the parent's
  primary-key index and each leaf is chunked by the same bytea order.
