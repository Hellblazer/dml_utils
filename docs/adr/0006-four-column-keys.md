# ADR 0006: Primary and ordered keys of four columns

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

A primary key or ordered key of one to three columns was supported (ADR 0001).
This builds on ADR 0004, which added the bytea kind: the key that motivated it
has a bytea column and four columns.
The nexus chunk table is keyed by four: `(tenant_id text, collection text,
chash bytea, embedding_model text)`. It is list-partitioned by `embedding_model`,
and each of those partitions by `tenant_id`, so a data move over it runs
partition-wise over a two-level tree, and also plain over the parent.

## Decision

### 1. The limit is four, in every place that states it

Nothing in the chunking path is written for a fixed number of columns: the
boundary packer, the descriptor, the flattening helpers, the box builder, the
chunk renderer and the leaf enumeration all loop over the key, and the box
builder already emits `2*(n-d)+1` boxes for any `n`. The limit lived in the
checks that guard the key's shape, and they change together:

- the resolver `primary_key_attributes` (which `primary_key_columns` and
  `primary_key_kinds` read through), and the ordered-key index check
  `assert_ordered_key_index_usable`;
- the shape guards of `boundary_key_descriptor_sql`, `boundary_key_pack_expression`,
  `migration_key_values`, `migration_key_pair_values`,
  `synthetic_chunk_boundary_values` and `migration_run_boundary_key`;
- `migration_key_is_canonical`, which gains the index-4 "exactly one array
  element" rule;
- the `migration_run_primary_key_shape_check` and
  `migration_run_ordered_key_shape_check` table checks.

A fifth column is rejected with `22023`, as a fourth was before.

### 2. The key type does not change

`dml_utils_data.migration_key` holds one array per key kind, each as long as the
key, so a four-column key fits the five arrays it has. No attribute is added and
no stored boundary or leaf terminal is touched. The bytea array stays the last
attribute, as ADR 0004 placed it. The bytea column may be in any position of the
key: the nexus key has it third in the primary key and last in the ordered key
over `(embedding_model, tenant_id, collection, chash)`.

### 3. New changesets for the table checks

The routines and `migration_key_is_canonical` are edited in place under
`runOnChange`, as in ADR 0004. The shape checks are constraints on an installed
table, so they are replaced in new changesets and the shipped ones stay as they
are:

- `012-allow-four-column-keys` replaces both checks through
  `ddl_utils.drop_constraint` and `ddl_utils.add_check_constraint`, adding the
  new checks `NOT VALID`;
- `013-validate-four-column-ordered-key-check` and
  `014-validate-four-column-primary-key-check` validate them with
  `ensure_check_constraint`, outside a transaction.

All three run after the functions and triggers includes and after 009 to 011,
like those. The new expressions are new properties, `..._four_columns`; the
properties that 009 to 011 and 009's rollback expand keep their names and
values, so a database that ran them expands the same text. Rolling back 012
restores the three-column checks `NOT VALID`; a run whose key has four columns
then fails every update with `23514` until it is archived and deleted. The
rollback file says so.

## Consequences

- A primary key or ordered key of one to four columns may be chunked, on a plain
  table and, partition-wise, on a partitioned parent of any depth.
- An installed database upgrades by running the changelog: the routines are
  replaced and 012 to 014 run. No run may be active, as for ADR 0004.
- The fixture that proved the rejection of a fourth column is now five columns.
  The benchmark scripts still build keys of one to three columns; they are not
  part of the library.
