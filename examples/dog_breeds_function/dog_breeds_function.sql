-- Function-over-table example: run a void function once per row of a dog-breeds
-- table, chunk by chunk, via dml_utils.run_function_over_table.
--
-- Run against the local dev database after scripts/start-local-db.sh:
--   psql -h localhost -p 5433 -U postgres -d dml_utils \
--       -f examples/dog_breeds_function/dog_breeds_function.sql
--
-- The script is idempotent: it archives any prior run with the example label,
-- then recreates the function and table in its own dog_breeds_function schema,
-- so it can be re-run freely and never touches a real table.

\set ON_ERROR_STOP on

-- Archive any previous run with the example label so the label is free for the
-- new run. archive_migration_run cancels an unfinished run first: cancelling
-- stops new chunks and marks the unclaimed boundaries before the DDL below runs,
-- so the DROP does not wait on workers that have not started a chunk. A chunk
-- that is already running may still finish its statement, so the DROP can
-- briefly wait for it.
SELECT dml_utils.archive_migration_run(i_label => 'dogs-review-function');

-- 1. A driving table with a supported primary key, in its own schema so the
--    example can never clobber a real table. Here the breed name (text) is the
--    key; a composite key of up to three columns works the same way. The
--    function below must take the primary-key column types in key order.
--    Drop the function first: a SQL function depends on the table it updates,
--    so the table cannot be dropped while the function still exists.
CREATE SCHEMA IF NOT EXISTS dog_breeds_function;
COMMENT ON SCHEMA dog_breeds_function IS
    'Example schema for the function-over-table demo.';

DROP FUNCTION IF EXISTS dog_breeds_function.review_dog(text);
DROP TABLE IF EXISTS dog_breeds_function.dogs;

CREATE TABLE dog_breeds_function.dogs
(
    breed    text PRIMARY KEY,
    origin   text,
    size     text,
    lifespan integer,
    status   text
);

COMMENT ON TABLE dog_breeds_function.dogs IS
    'Example: dog breeds, keyed by breed name, for the function-over-table demo.';
COMMENT ON COLUMN dog_breeds_function.dogs.status IS
    'Set per row by the example review_dog function.';

-- The boundary scan runs inside the owner-owned definer routine, so the owner
-- must be able to read the driving table; a non-superuser caller also needs the
-- DML its per-row function performs (see "Requirements and behavior" in the
-- README).
GRANT USAGE ON SCHEMA dog_breeds_function TO dml_utils_owner, dml_utils_caller;
GRANT SELECT, UPDATE ON dog_breeds_function.dogs TO dml_utils_owner, dml_utils_caller;

-- 2. Seed with some famous breeds.
INSERT INTO dog_breeds_function.dogs (breed, origin, size, lifespan)
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

-- 3. Encapsulate the per-row work in a void function. Its argument list is the
--    driving table's primary key, in key order (here a single text breed).
--    The wrapper validates this signature up front and calls the function once
--    per row, so the caller never writes the template SQL.
CREATE OR REPLACE FUNCTION dog_breeds_function.review_dog(p_breed text)
    RETURNS void
    LANGUAGE sql
    SECURITY INVOKER
AS
$$
UPDATE dog_breeds_function.dogs
SET status = 'reviewed'
WHERE breed = p_breed;
$$;

COMMENT ON FUNCTION dog_breeds_function.review_dog IS
    'Example per-row function: marks one dog breed reviewed.';

-- 4. Inspect the plans the run would use, before running it.
--    explain_function_over_table is read-only: it creates no run and writes no
--    boundaries, and the chunk ranges are synthetic, so the row estimates may
--    differ from a real chunk. The three rows show the primary-key scan the
--    boundaries are computed from and the two generated per-row call forms (a
--    half-open range and the final, inclusive one).
\echo '--- plans for the function-over-table run (read-only) ---'
SELECT o_plan_kind,
       o_sql_text,
       jsonb_pretty(o_plan::jsonb) AS plan
FROM dml_utils.explain_function_over_table(
        i_driving_table_schema_name => 'dog_breeds_function',
        i_driving_table_name => 'dogs',
        i_function_schema_name => 'dog_breeds_function',
        i_function_name => 'review_dog',
        i_chunk_size => 10);

-- 5. Run the function over every row in small chunks. The wrapper builds the
--    per-row template and delegates to run_migration_chunks, so each chunk is a
--    pg_background worker and the call resumes at the first unprocessed chunk if
--    it is interrupted. The label identifies the run; re-running with the same
--    label resumes it, and an already-complete run is a no-op. An earlier run
--    with the label was archived at the top so the example re-runs from scratch.
SELECT dml_utils.run_function_over_table(
               i_driving_table_schema_name => 'dog_breeds_function',
               i_driving_table_name => 'dogs',
               i_function_schema_name => 'dog_breeds_function',
               i_function_name => 'review_dog',
               i_chunk_size => 10,
               i_threads => 2,
               i_label => 'dogs-review-function');

-- 6. Inspect the result: run summary, stored boundaries, status counts.
\echo '--- migration run summary ---'
SELECT run_id,
       label,
       chunk_size,
       threads,
       boundary_count,
       completed_boundary_count,
       error_count,
       completed_at IS NOT NULL AS completed
FROM dml_utils.migration_run_summary(i_label => 'dogs-review-function')
WHERE archived_at IS NULL;

\echo '--- stored chunk boundaries (boundary_id as text values) ---'
SELECT b.boundary_no,
       dml_utils_lib.migration_key_values(
               b.boundary_id,
               dml_utils_lib.primary_key_kinds(
                       i_schema_name => 'dog_breeds_function',
                       i_table_name => 'dogs')) AS key_values,
       b.completed_at IS NOT NULL               AS completed
-- noqa:disable=AM05,ST09
FROM dml_utils_data.migration_run AS r
         JOIN dml_utils_data.migration_boundary AS b ON b.run_id = r.run_id
-- noqa:enable=AM05,ST09
WHERE r.label = 'dogs-review-function'
  AND r.archived_at IS NULL
ORDER BY b.boundary_no;

\echo '--- rows by status ---'
SELECT status,
       count(*) AS dogs
FROM dog_breeds_function.dogs
GROUP BY status
ORDER BY status;

\echo '--- a few processed rows ---'
SELECT breed,
       origin,
       size,
       lifespan,
       status
FROM dog_breeds_function.dogs
ORDER BY breed
LIMIT 10;
