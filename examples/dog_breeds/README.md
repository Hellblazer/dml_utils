# Chunked migration example: dog breeds

A runnable, self-contained example of `dml_utils.run_migration_chunks` against a
small `dogs` table keyed by breed.

## What it does

`dog_breeds.sql`:

1. Creates `public.dogs` (one row per dog breed) and an `status` column to
   backfill.
2. Seeds it with famous breeds.
3. Prints the plans the run would use via `dml_utils.explain_migration_chunks`
   (read-only: no run or boundaries are written, and the chunk ranges are
   synthetic, so the estimates may differ from a real chunk).
4. Runs `dml_utils.run_migration_chunks` to set `status = 'reviewed'` for every
   row, chunk by chunk (small chunk size so the example makes several chunks).
5. Prints the run summary, the stored boundaries, and the per-status counts.

The script is idempotent: it drops and recreates the `dogs` table and archives
any previous run with the same label, so you can re-run it freely.

## Run it

Start the local database (applies the changelog as `dml_utils_owner`):

```sh
scripts/start-local-db.sh
```

Then run the example as the `postgres` superuser (which can create the table and
is a member of `pgbackground_role`):

```sh
psql -h localhost -p 5433 -U postgres -d dml_utils -f examples/dog_breeds/dog_breeds.sql
```

If you want to start from a clean database first:

```sh
scripts/refresh-local-db.sh
psql -h localhost -p 5433 -U postgres -d dml_utils -f examples/dog_breeds/dog_breeds.sql
```

## Notes

- The driving table must have a primary key of one to three columns, each of a
  supported type (`smallint`, `integer`, `bigint`, `text`, `uuid`, `timestamptz`
  or `bytea`). This
  example uses a single `text` key (the breed name); change the primary key in
  the script to try a composite or another kind.
- `pg_background` must be installed and the calling role must hold
  `pgbackground_role`; the custom image's init script grants it to
  `dml_utils_caller` (and the local `postgres` superuser can always call it).
