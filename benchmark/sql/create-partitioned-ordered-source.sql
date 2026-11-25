-- Deterministic partition-wise ordered-key benchmark source table.
--
-- The ordered_key fixture shape (a UUID primary key with a (created_at, id)
-- unique index) made declaratively partitioned by hashing the primary key, so a
-- partition-wise ordered_key run has several leaves to calculate and process.
-- The hash partition key is `id`, which is part of both the primary key and the
-- (created_at, id) ordered index, so PostgreSQL accepts both unique constraints
-- on the parent and creates the matching child indexes automatically.
--
-- Run by benchmark/setup.sh with :schema, :source_table, :rows, :index and
-- :leaves set. The :index variable must stay on one line: a psql variable is
-- only substituted when the name follows the colon with no whitespace.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

COMMENT ON SCHEMA :"schema" IS
    'Benchmark fixture schema for the synthetic sweep.';

DROP TABLE IF EXISTS :"schema".:"source_table";

CREATE TABLE :"schema".:"source_table"
(
    id         uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL,
    payload    text        NOT NULL
) PARTITION BY HASH (id);

COMMENT ON TABLE :"schema".:"source_table" IS
    'Benchmark fixture: the deterministic UUIDv4-keyed ordered rows, hash-partitioned by id for the partition-wise ordered_key variant.';
COMMENT ON COLUMN :"schema".:"source_table".id IS
    'Deterministic UUIDv4-format primary key and hash partition key.';
COMMENT ON COLUMN :"schema".:"source_table".created_at IS
    'Deterministic timestamp; ten consecutive rows share one value, so id breaks ties.';
COMMENT ON COLUMN :"schema".:"source_table".payload IS
    'Deterministic payload derived from the row number.';

-- One hash leaf per requested partition.
SELECT pg_catalog.format(
               'CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
               :'schema', :'source_table' || '_p' || i,
               :'schema', :'source_table',
               :'leaves'::integer, i)
FROM pg_catalog.generate_series(0, :'leaves'::integer - 1) AS i
ORDER BY i
\gexec

-- One comment per generated leaf, so the fixture is as self-describing as the
-- hand-written ones.
SELECT pg_catalog.format('COMMENT ON TABLE %I.%I IS %L',
                         :'schema', :'source_table' || '_p' || i,
                         'Benchmark fixture: hash leaf ' || i || ' of ' || :'leaves'::text
                             || ' for the partition-wise ordered_key variant.')
FROM pg_catalog.generate_series(0, :'leaves'::integer - 1) AS i
ORDER BY i
\gexec

-- The UUID is deterministic: md5 of the row number with the version (4) and
-- variant (8) nibbles set, so a refresh reproduces the table exactly. The
-- timestamp pairs ten rows per second, so id is the tiebreaker.
INSERT INTO :"schema".:"source_table" (id, created_at, payload)
SELECT pg_catalog.overlay(
               pg_catalog.overlay(pg_catalog.md5(g::text), '4', 13, 1),
               '8', 17, 1)::uuid,
       timestamptz '2020-01-01 00:00:00+00'
           + ((g - 1) / 10) * interval '1 second',
       pg_catalog.md5(g::text)
FROM pg_catalog.generate_series(1, :'rows'::bigint) AS g;

-- Build the index once after the bulk load: maintaining it row by row during
-- the INSERT costs more than one sorted build per partition.
CREATE UNIQUE INDEX :"index"
    ON :"schema".:"source_table" (created_at, id);

COMMENT ON INDEX :"schema".:"index" IS
    'The partition-wise ordered-key index: (created_at, id), with id as the tiebreaker.';

-- Refresh planner statistics (see create-source.sql).
ANALYZE :"schema".:"source_table";
-- ANALYZE on the partitioned parent does not refresh the leaves, and the
-- partition-wise chunks are planned per leaf, so analyze each storage leaf too.
SELECT pg_catalog.format('ANALYZE %I.%I', n.nspname, c.relname)
FROM pg_catalog.pg_partition_tree(pg_catalog.format('%I.%I', :'schema', :'source_table')::regclass) AS leaf
         JOIN pg_catalog.pg_class AS c ON c.oid = leaf.relid
         JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
WHERE leaf.isleaf
\gexec

