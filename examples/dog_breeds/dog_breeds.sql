-- Chunked migration example: backfill a column on a dog-breeds table.
--
-- Run against the local dev database after scripts/start-local-db.sh:
--   psql -h localhost -p 5433 -U postgres -d dml_utils -f examples/dog_breeds/dog_breeds.sql
--
-- The script is idempotent: it recreates the table and archives any prior run
-- with the example label, so it can be re-run freely.

\set ON_ERROR_STOP on

-- Stop and archive any previous run before replacing the driving table, so an
-- in-flight pg_background worker cannot hold locks on the old relation.
SELECT dml_utils.cancel_migration_run(i_label => 'dogs-status-backfill');
SELECT dml_utils.archive_migration_run(i_label => 'dogs-status-backfill');

-- 1. A driving table with a supported primary key, in its own schema so the
--    example can never clobber a real public.dogs table. Here the breed name
--    (text) is the key; a composite key of up to three columns works the same
--    way.
CREATE SCHEMA IF NOT EXISTS dog_breeds;
COMMENT ON SCHEMA dog_breeds IS
    'Example schema for the dog-breeds chunked-migration demo.';

DROP TABLE IF EXISTS dog_breeds.dogs;

CREATE TABLE dog_breeds.dogs
(
    breed    text PRIMARY KEY,
    origin   text,
    size     text,
    lifespan integer,
    status   text
);

COMMENT ON TABLE dog_breeds.dogs IS
    'Example: dog breeds, keyed by breed name, for the chunked-migration demo.';
COMMENT ON COLUMN dog_breeds.dogs.status IS
    'Backfilled by the example migration.';

-- 2. Seed with some famous breeds.
INSERT INTO dog_breeds.dogs (breed, origin, size, lifespan)
VALUES ('Affenpinscher', 'Germany', 'toy', 14),
       ('Afghan Hound', 'Afghanistan', 'large', 13),
       ('Airedale Terrier', 'United Kingdom', 'large', 12),
       ('Akita', 'Japan', 'large', 11),
       ('Alaskan Malamute', 'United States', 'large', 13),
       ('American Bulldog', 'United States', 'large', 12),
       ('Australian Shepherd', 'United States', 'medium', 14),
       ('Basenji', 'Democratic Republic of the Congo', 'small', 14),
       ('Basset Hound', 'France', 'medium', 12),
       ('Beagle', 'United Kingdom', 'small', 14),
       ('Bernese Mountain Dog', 'Switzerland', 'large', 9),
       ('Bichon Frise', 'France', 'small', 15),
       ('Bloodhound', 'Belgium', 'large', 11),
       ('Border Collie', 'United Kingdom', 'medium', 14),
       ('Boston Terrier', 'United States', 'small', 13),
       ('Boxer', 'Germany', 'large', 11),
       ('Bulldog', 'United Kingdom', 'medium', 10),
       ('Cane Corso', 'Italy', 'large', 11),
       ('Chihuahua', 'Mexico', 'toy', 16),
       ('Chow Chow', 'China', 'medium', 12),
       ('Cocker Spaniel', 'United Kingdom', 'medium', 13),
       ('Dachshund', 'Germany', 'small', 15),
       ('Dalmatian', 'Croatia', 'large', 12),
       ('Dobermann', 'Germany', 'large', 11),
       ('English Springer Spaniel', 'United Kingdom', 'medium', 13),
       ('French Bulldog', 'France', 'small', 12),
       ('German Shepherd', 'Germany', 'large', 11),
       ('Golden Retriever', 'United Kingdom', 'large', 12),
       ('Great Dane', 'Germany', 'giant', 9),
       ('Greyhound', 'United Kingdom', 'large', 13),
       ('Havanese', 'Cuba', 'small', 15),
       ('Irish Setter', 'Ireland', 'large', 13),
       ('Jack Russell Terrier', 'United Kingdom', 'small', 15),
       ('Labrador Retriever', 'Canada', 'large', 12),
       ('Maltese', 'Malta', 'toy', 15),
       ('Newfoundland', 'Canada', 'giant', 10),
       ('Papillon', 'France', 'toy', 16),
       ('Pomeranian', 'Germany', 'toy', 15),
       ('Poodle', 'Germany', 'medium', 14),
       ('Pug', 'China', 'small', 13),
       ('Rottweiler', 'Germany', 'large', 10),
       ('Saint Bernard', 'Switzerland', 'giant', 9),
       ('Samoyed', 'Russia', 'large', 13),
       ('Shar Pei', 'China', 'medium', 11),
       ('Shiba Inu', 'Japan', 'small', 14),
       ('Shih Tzu', 'China', 'toy', 14),
       ('Siberian Husky', 'Russia', 'large', 13),
       ('Staffordshire Bull Terrier', 'United Kingdom', 'medium', 13),
       ('Vizsla', 'Hungary', 'large', 13),
       ('Weimaraner', 'Germany', 'large', 12),
       ('Whippet', 'United Kingdom', 'medium', 14),
       ('Yorkshire Terrier', 'United Kingdom', 'toy', 15);

-- 3. Inspect the plans the run would use, before running it.
--    explain_migration_chunks is read-only: it creates no run and writes no
--    boundaries, and the chunk ranges are synthetic, so the row estimates may
--    differ from a real chunk. The three rows show the primary-key scan the
--    boundaries are computed from and the two generated chunk forms (a
--    half-open range and the final, inclusive one).
\echo '--- plans for the chunked migration (read-only) ---'
SELECT o_plan_kind,
       o_sql_text,
       jsonb_pretty(o_plan::jsonb) AS plan
FROM dml_utils.explain_migration_chunks(
        i_sql_text => 'UPDATE <driving_table> SET status = ''reviewed'' WHERE <chunking_clause>',
        i_driving_table_schema_name => 'dog_breeds',
        i_driving_table_name => 'dogs',
        i_chunk_size => 10);

-- 4. Run the migration in small chunks. The template sets status = 'reviewed'
--    on every row, chunk by chunk; each chunk is a pg_background worker, and
--    the call resumes at the first unprocessed chunk if it is interrupted.
--
--    The label identifies the run; re-running with the same label resumes it,
--    and an already-complete run is a no-op. The previous run was cancelled and
--    archived at the top so the example can be re-run from scratch.
SELECT dml_utils.run_migration_chunks(
               i_sql_text => 'UPDATE <driving_table> SET status = ''reviewed'' WHERE <chunking_clause>',
               i_driving_table_schema_name => 'dog_breeds',
               i_driving_table_name => 'dogs',
               i_label => 'dogs-status-backfill',
               i_chunk_size => 10,
               i_threads => 2);

-- 5. Inspect the result: run summary, stored boundaries, status counts.
\echo '--- migration run summary ---'
SELECT run_id,
       label,
       chunk_size,
       threads,
       boundary_count,
       completed_boundary_count,
       error_count,
       completed_at IS NOT NULL AS completed,
       archived_at
FROM dml_utils.migration_run_summary(i_label => 'dogs-status-backfill')
WHERE archived_at IS NULL;

\echo '--- stored chunk boundaries (boundary_id as text values) ---'
SELECT b.boundary_no,
       dml_utils_lib.migration_key_values(b.boundary_id, ARRAY ['text']) AS key_values,
       b.completed_at IS NOT NULL                                        AS completed
-- noqa:disable=AM05,ST09
FROM dml_utils_data.migration_run AS r
         JOIN dml_utils_data.migration_boundary AS b ON b.run_id = r.run_id
-- noqa:enable=AM05,ST09
WHERE r.label = 'dogs-status-backfill'
  AND r.archived_at IS NULL
ORDER BY b.boundary_no;

\echo '--- rows by status ---'
SELECT status,
       count(*) AS dogs
FROM dog_breeds.dogs
GROUP BY status
ORDER BY status;

\echo '--- a few processed rows ---'
SELECT breed,
       origin,
       size,
       lifespan,
       status
FROM dog_breeds.dogs
ORDER BY breed
LIMIT 10;
