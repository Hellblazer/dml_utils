# Function-over-table example: dog breeds

A runnable, self-contained example of `dml_utils.run_function_over_table` — the
wrapper that calls a `void` function once per row of a driving table, chunk by
chunk — against a small `dogs` table keyed by breed.

## What it does

`dog_breeds_function.sql`:

1. Archives any earlier run with the example label.
2. Creates `dog_breeds_function.dogs` (one row per dog breed) and a `status`
   column to fill, in its own schema so the example never touches a real table.
3. Seeds it with famous breeds.
4. Defines `dog_breeds_function.review_dog(p_breed text)`, a `void` function
   whose single argument is the table's primary key.
5. Prints the plans the run would use via
   `dml_utils.explain_function_over_table` (read-only: no run or boundaries are
   written, and the chunk ranges are synthetic, so the estimates may differ from
   a real chunk).
6. Runs `dml_utils.run_function_over_table`, which builds the per-row template,
   validates the function against the primary key, and delegates to
   `run_migration_chunks` (small chunk size so the example makes several chunks).
7. Prints the run summary, the stored boundaries, and the per-status counts.

The script is idempotent: it archives any previous run with the same label, then
drops and recreates the function and the `dogs` table in the
`dog_breeds_function` schema, so you can re-run it freely.

## Run it

Start the local database (applies the changelog as `dml_utils_owner`):

```sh
scripts/start-local-db.sh
```

Then run the example as the `postgres` superuser (which can create the schema and
table and is a member of `pgbackground_role`):

```sh
psql -h localhost -p 5433 -U postgres -d dml_utils \
    -f examples/dog_breeds_function/dog_breeds_function.sql
```

If you want to start from a clean database first:

```sh
scripts/refresh-local-db.sh
psql -h localhost -p 5433 -U postgres -d dml_utils \
    -f examples/dog_breeds_function/dog_breeds_function.sql
```

## Notes

- The function must return `void` and take the driving table's primary-key column
  types in key order; `run_function_over_table` raises `22023` before starting a
  run if the signature does not match. This example uses a single `text` key (the
  breed name); change the primary key in the script to try a composite or another
  kind.
- When `i_label` is omitted, the wrapper derives a deterministic label from the
  table and function, so re-running the same call resumes the same run. The
  example passes an explicit label so it can archive it first and re-run cleanly.
- A resumed run reuses the recorded template. To point an unfinished run at a
  different function, call `dml_utils.set_migration_run_function`.
- `pg_background` must be installed and the calling role must hold
  `pgbackground_role`; the custom image's init script grants it to
  `dml_utils_caller` (and the local `postgres` superuser can always call it).
