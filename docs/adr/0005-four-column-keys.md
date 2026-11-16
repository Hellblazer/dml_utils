# ADR 0005: Primary and ordered keys of four columns

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

A primary key or ordered key of one to three columns was supported (ADR 0001).
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

- the resolvers `primary_key_columns` and `primary_key_attributes`, and the
  ordered-key index check `assert_ordered_key_index_usable`;
- the shape guards of `boundary_key_descriptor_sql`, `boundary_key_pack_expression`,
  `migration_key_values`, `migration_key_pair_values` and
  `synthetic_chunk_boundary_values`;
- `migration_key_is_canonical`, which gains the index-4 "exactly one array
  element" rule;
- the `migration_run_ordered_key_shape_check` table check.

A fifth column is rejected with `22023`, as a fourth was before.

### 2. The key type does not change

`dml_utils_data.migration_key` holds one array per key kind, each as long as the
key, so a four-column key fits the five arrays it has. No attribute is added and
no stored boundary or leaf terminal is touched. The bytea array stays the last
attribute, as ADR 0003 placed it. The bytea column may be in any position of the
key: the nexus key has it third in the primary key and last in the ordered key
over `(embedding_model, tenant_id, collection, chash)`.

### 3. New changesets for the table check

The routines and `migration_key_is_canonical` are edited in place under
`runOnChange`, as in ADR 0003. The shape check is a constraint on an installed
table, so it is replaced in two new changesets and the shipped ones stay as they
are:

- `011-allow-four-column-ordered-key` replaces the check through
  `ddl_utils.drop_constraint` and `ddl_utils.add_check_constraint`, adding the new
  check `NOT VALID`;
- `012-validate-four-column-ordered-key-check` validates it with
  `ensure_check_constraint`, outside a transaction.

Both run after the functions and triggers includes and after 009 and 010, like
those two. The new expression is a new property, `..._four_columns`; the two
properties that 009, 010 and 009's rollback expand keep their names and values,
so a database that ran them expands the same text. Rolling back 011 restores the
three-column check `NOT VALID`; a run whose ordered key has four columns then
fails every update with `23514` until it is archived and deleted. The rollback
file says so.

## Consequences

- A primary key or ordered key of one to four columns may be chunked, on a plain
  table and, partition-wise, on a partitioned parent of any depth.
- An installed database upgrades by running the changelog: the routines are
  replaced and 011 and 012 run. No run may be active, as for ADR 0003.
- The fixture that proved the rejection of a fourth column is now five columns.
  The benchmark scripts still build keys of one to three columns; they are not
  part of the library.
