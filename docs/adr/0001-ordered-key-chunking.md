# ADR 0001: Ordered-key chunking with unique indexes

- **Status:** Accepted
- **Date:** 2026-11-01
- **Beads:** `dml-b87` (epic), `dml-b87.1` (this design)

## Context

`dml_utils.run_migration_chunks` currently splits a table into chunks in one of
two orders:

- `primary_key` — ordered ranges over the primary key, 1–3 columns of bigint,
  text or uuid. The boundary query orders the rows by the key and inserts one
  boundary per chunk start plus a terminal boundary; each chunk renders a
  lexicographic range.
- `blocks` — physical heap ranges (ctid). This requires a plain heap and a
  quiescent source because a heap rewrite or row movement changes ctid.

Neither fits every table:

- A UUIDv4 primary key has no useful order: the boundary scan and every chunk
  do random I/O, and the reported ranges are meaningless.
- A table often has a much better clustering key than its PK, for example
  `created_at` or a natural key. A `(created_at, id)` index gives the workload
  an ordered, index-aligned scan while the PK remains a surrogate.
- `blocks` is not a substitute: it is physical, requires a quiescent source and
  does not target an index.

The goal is therefore an **ordered key** strategy: keep the existing range
engine but let the caller choose the ordered, unique key from an index.

No dml_utils changelog has been deployed anywhere, so this design also adopts
the pre-deployment baseline policy: edit the create changesets and routine files
in place instead of adding compatibility changesets or backfills, and suspend
`LiquibaseUpgradeTest` while the baseline changes (tracked in `dml-b87.12`,
restored in `dml-b87.13`).

## Decision

### 1. A new `ordered_key` strategy

Add `ordered_key` to `dml_utils_data.chunking_strategy`:

| Value | Meaning |
|---|---|
| `primary_key` | Ordered ranges over the primary key (default, unchanged) |
| `ordered_key` | Ordered ranges over a caller-selected unique index |
| `blocks` | Physical heap block ranges (unchanged) |

`primary_key` and `ordered_key` use the same boundary and rendering machinery.
The split makes the run metadata truthful (a run is not "primary_key" when it
used a secondary index) and gives the eligibility checks a distinct branch.

The enum is edited in `004-create-domains.sql` in place; no `ALTER TYPE ... ADD
VALUE` follow-up is needed while nothing is deployed.

### 2. The caller names an index, not a column list

Add a trailing parameter:

```sql
run_migration_chunks(
    ...,
    i_chunk_by => 'ordered_key',
    i_boundary_key_index => 'orders_created_at_id_key'
)
```

The index is resolved in the driving table's schema. Naming the index rather
than passing free-form columns means:

- an index is proof that the order the caller wants actually exists;
- the catalog provides the column order, types, collation and opclass, so the
  rendered predicate matches the index;
- the run can re-resolve and re-validate the same key on resume.

A column list is deliberately not part of the v1 API. Its only advantage over
an index name is avoiding an index rename, which the resume check would catch
anyway.

### 3. v1 requires a unique btree index on 1–3 simple columns

The index must satisfy all of:

| Check | Rule |
|---|---|
| Kind | btree, not an exclusion constraint |
| Uniqueness | `indisunique` |
| Health | `indisvalid`, `indisready`, `indislive` |
| Shape | `indpred IS NULL`, `indexprs IS NULL`, `indnkeyatts BETWEEN 1 AND 3` |
| Columns | The first `indnkeyatts` `indkey` entries are non-zero simple columns; included columns are ignored |
| Ordering | `indoption` is all zero (ASC, NULLS LAST); DESC / NULLS FIRST are rejected in v1 |
| Opclass | the default btree opclass for each key column |
| Collation | `indcollation` matches the column's `attcollation` |
| Nullability | every key column is `attnotnull` |
| Type | bigint (smallint/integer/bigint), text, uuid or timestamptz |
| Table | `relkind = 'r'`; partitioned parents are rejected (the partition-wise epic owns them) |

A non-unique index plus a separate tiebreaker is **deferred**: the useful form
is a composite unique index such as `(created_at, id)`, which this design
already supports. A non-unique `(created_at)` index with the PK as a tiebreaker
would not be fully index-ordered and adds predicate complexity for little gain.

### 4. Supported key types

v1 supports the same collapsed kinds as the primary key plus timestamptz:

| SQL type | Key kind | `migration_key` array |
|---|---|---|
| smallint, integer, bigint | `bigint` | `bigint_values` |
| text | `text` | `text_values` |
| uuid | `uuid` | `uuid_values` |
| timestamptz | `timestamptz` | `timestamptz_values` (new) |

`date`, `numeric` and other orderable types are deferred. The integer family
(smallint/integer/bigint) collapses to `bigint` and is supported as today.

### 5. Schema changes (edited in place)

- `004-create-domains.sql`
  - add the `ordered_key` enum label;
  - add `timestamptz_values timestamptz[]` to `dml_utils_data.migration_key`;
  - update the type and enum comments.
- `005-create-migration-key-check.sql`
  - extend `migration_key_is_canonical` (arity, one-dimensional/1-based arrays,
    exactly one non-NULL element per position) and its comment for the fourth
    array;
  - update `migration_key_values` and `migration_key_pair_values`.
- `006-create-migration-tables.sql`
  - add to `migration_run`:
    ```sql
    ordered_key_index   name,
    ordered_key_columns name[],
    ordered_key_kinds   text[],
    ```
  - constrain the combinations:
    ```
    (chunk_by = 'ordered_key') = (
        ordered_key_index IS NOT NULL AND
        ordered_key_columns IS NOT NULL AND
        ordered_key_kinds IS NOT NULL)
    ```
    and keep `ordered_key` runs without a `driving_table_relation_filepath`;
  - update the `chunk_by` / `chunk_size` comments.
  - The descriptor is stored so explain and resume use the exact key the
    boundaries were built from. Invalid combinations fail with a clear error
    from the resolver; the check constraint is the last line of defence.

The `migration_key` change regenerates `MigrationKeyRecord`, so every Java
constructor call (tests and fixtures) gains the new array.

### 6. Resolution and validation

Add a catalog helper that resolves the index to:

- ordered key columns (name, type, collapsed kind, collation);
- the index OID and the table's relation kind.

A single `assert_usable_ordered_key(schema, table, index)` performs the table
checks above and is used by both the run path and resume. Reject with:

| Situation | SQLSTATE |
|---|---|
| Missing index | `42704` (`undefined_object`) |
| Missing table/schema | the existing `assert_table_exists`/`assert_schema_exists` codes |
| Missing/invalid parameters, unsupported type, wrong arity | `22023` (`invalid_parameter_value`) |
| Unsupported index shape/table/strategy combination | `0A000` (`feature_not_supported`) |
| Index invalid/dropped on resume | `55000` (`object_not_in_prerequisite_state`) |
| Missing privileges | `42501` (existing convention) |

The existing `primary_key_attributes` reader is the base; generalise it (or add
an index variant) so the primary-key path and the ordered-key path share the
type/kind mapping and collation handling.

### 7. Boundaries and chunk rendering

The current range engine already tiles a lexicographic range as a disjunction
of axis-aligned boxes, so the change is mostly type plumbing:

- `build_boundary_population_sql` takes generic key columns/kinds and packs a
  `migration_key` with the new timestamptz array;
- `prepare_chunk_sql_render` / `render_prepared_chunk_sql` add the timestamptz
  cast and keep using the resolved key collations;
- the box tiling, inclusive final bound and NULL rejection are unchanged.

The boundary sequence stays 0..N with one terminal boundary; `total_chunks`
continues to be the boundary count minus the terminal boundary. `ordered_key`
and `primary_key` share this path.

### 8. API and scope

- `run_migration_chunks` and `populate_migration_boundaries` gain
  `i_boundary_key_index name DEFAULT NULL` as a trailing parameter.
- `chunk_by = 'ordered_key'` requires a non-NULL index; `primary_key` and
  `blocks` require it to be NULL. A mismatch is an invalid-parameter error.
- The default remains `primary_key`; existing calls are unchanged.
- `run_function_over_table` stays on `primary_key` / `blocks` in v1: its
  contract is that the user function receives the primary-key column types in
  key order. Supporting an ordered key would change that contract; it is a
  follow-up, not part of this decision.
- `explain_migration_chunk` gains `i_chunk_by` and `i_boundary_key_index` so a
  synthetic chunk can be rendered for an ordered key; the run-based explain
  reads the stored descriptor. `explain_function_over_table_chunk` is unchanged.

Because this is a pre-deployment baseline edit, the function definitions and
rollbacks are edited in place; no legacy overload or `DROP ... IF EXISTS` shim
is kept. Grants in `999-grant-privileges.sql` are edited in place.

### 9. Resume and the key contract

The run stores the index, columns and kinds. On resume:

- re-resolve the index and compare it to the stored descriptor;
- reject a dropped, invalid, partial, reordered or differently-typed index
  with `55000` rather than silently rebuilding boundaries;
- do not recompute boundaries: `boundaries_calculated_at` means the existing
  boundaries are authoritative.

The key columns must not be modified by the DML template. This is a caller
contract, exactly as it is for `primary_key`; it is documented, not detected
(runtime detection would require inspecting the template, which is not reliable).
A run whose key is mutated should be archived and restarted after the data is
corrected.

## Examples

```sql
-- A surrogate UUID primary key with a useful time/ID order.
CREATE UNIQUE INDEX orders_created_at_id_key
    ON public.orders (created_at, id);

SELECT dml_utils.run_migration_chunks(
    i_sql_text => 'UPDATE <driving_table> SET processed_at = now() WHERE <chunking_clause>',
    i_driving_table_schema_name => 'public',
    i_driving_table_name => 'orders',
    i_chunk_size => 10000,
    i_threads => 4,
    i_chunk_by => 'ordered_key',
    i_boundary_key_index => 'orders_created_at_id_key'
);
```

## Alternatives considered

- **Overload `primary_key` with an optional index override.** Rejected: the run
  would claim `primary_key` while using a different order, making status,
  explain and resume misleading.
- **Accept a column list instead of an index.** Rejected for v1: it cannot
  prove an index exists or that its order/collation matches, and it invites
  keys that are not usable for the range predicate.
- **Non-unique index plus a tiebreaker.** Deferred: the supported form is a
  composite unique index; a separate tiebreaker adds predicate complexity and
  often still needs the composite index.
- **A JSONB descriptor or a separate descriptor table.** Rejected: three
  columns on `migration_run` are queryable, constrained and consistent with the
  existing style.
- **Support partitioned parents now.** Deferred to the partition-wise epic
  (`dml-sw6`), which owns leaf enumeration and per-leaf boundaries.
- **Hash/bucket or snapshot key lists.** Separate strategies; not part of this
  decision.

## Consequences

Positive:

- UUIDv4 / natural / time keys get index-aligned ranges without changing the
  table's primary key.
- The same boundary, resume and explain machinery is reused; `primary_key` and
  `blocks` are untouched.
- The run records exactly which key was used, so resume and explain cannot
  drift.

Negative / risks:

- The index contract is strict (unique, NOT NULL, default opclass, ASC); tables
  without such an index must add one.
- `migration_key` grows and regenerates the jOOQ record; every constructor use
  must be updated.
- `run_function_over_table` does not support the new strategy in v1.
- Key mutability is a documented contract, not enforced.
- An index dropped or rebuilt with a different definition fails a resumed run
  by design; the run must be archived and restarted.

## Baseline policy

Nothing is deployed, so:

- edit `004`, `005`, `006`, the routine files and `999-grant-privileges.sql` in
  place;
- no compatibility changesets, `ALTER TYPE`/`ALTER TABLE` follow-ups or
  backfills;
- recreate local/dev/benchmark databases instead of migrating them;
- `LiquibaseUpgradeTest` is suspended in `dml-b87.12` and restored in
  `dml-b87.13` against a release cut from the cleaned changelog.

## Implementation map

| Bead | Work |
|---|---|
| `dml-b87.2` | `migration_key` + timestamptz key kind |
| `dml-b87.3` | `migration_run` descriptor columns/constraints |
| `dml-b87.4` | resolve and validate the index |
| `dml-b87.5` | boundary population |
| `dml-b87.6` | chunk clause rendering and explain |
| `dml-b87.7` | API on run/populate |
| `dml-b87.8` | eligibility and resume validation |
| `dml-b87.9` | integration coverage |
| `dml-b87.10` | docs |
| `dml-b87.11` | benchmark variant |

## Non-goals

- Partitioned driving tables (epic `dml-sw6`).
- Hash/bucket or snapshot/claim-based chunking.
- Non-unique indexes without a composite unique key.
- Expression, partial, exclusion, non-default opclass, DESC / NULLS FIRST
  indexes.
- Date/numeric key types (deferred).
- `run_function_over_table` support.
- Runtime detection of a mutated key.
