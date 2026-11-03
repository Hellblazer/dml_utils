-- Deterministic partition-wise benchmark source table.
--
-- A range-partitioned copy of the synthetic source: the same key tuples and
-- payloads as create-source.sql, split across :leaves leaves on the first key
-- column, so a partition-wise run has several comparable leaves to work
-- through. Run by benchmark/setup.sh with :schema, :source_table, :rows,
-- :pk_def, :pk_key, :pk_exprs, :leaves and :k1_max set. :k1_max is the largest
-- value of k1 that the deterministic key generator produces, so the leaves are
-- balanced. The :pk_def, :pk_key and :pk_exprs variables must each stay on one
-- line: a psql variable is only substituted when the name follows the colon
-- with no whitespace.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

DROP TABLE IF EXISTS :"schema".:"source_table";

CREATE TABLE :"schema".:"source_table"
(
    :pk_def,
    payload text NOT NULL,
    PRIMARY KEY (:pk_key)
) PARTITION BY RANGE (k1);

COMMENT ON TABLE :"schema".:"source_table" IS
    'Benchmark fixture: the deterministic synthetic rows, range-partitioned by k1 for the partition-wise variant.';
COMMENT ON COLUMN :"schema".:"source_table".k1 IS
    'First key column; also the range partition key.';
COMMENT ON COLUMN :"schema".:"source_table".payload IS
    'Deterministic payload derived from the row number.';

-- Split 1..:k1_max into :leaves contiguous ranges. The last leaf ends at
-- MAXVALUE so it also catches any value above :k1_max.
SELECT pg_catalog.format(
               'CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES FROM (%s) TO (%s)',
               :'schema', :'source_table' || '_p' || i,
               :'schema', :'source_table',
               lo,
               coalesce(hi::text, 'MAXVALUE'))
FROM (SELECT i,
             1 + (i * :k1_max) / :leaves AS lo,
             CASE
                 WHEN i = :leaves - 1 THEN NULL
                 ELSE 1 + ((i + 1) * :k1_max) / :leaves
                 END                     AS hi
      FROM pg_catalog.generate_series(0, :leaves - 1) AS i) AS bounds
ORDER BY i
\gexec

-- One comment per generated leaf, so the fixture is as self-describing as the
-- hand-written ones.
SELECT pg_catalog.format('COMMENT ON TABLE %I.%I IS %L',
                         :'schema', :'source_table' || '_p' || i,
                         'Benchmark fixture: range leaf ' || i || ' of ' || :leaves
                             || ' for the partition-wise variant.')
FROM pg_catalog.generate_series(0, :leaves - 1) AS i
ORDER BY i
\gexec

-- The row number always yields the same key tuple and payload, so refreshing the
-- database reproduces the table exactly. :pk_exprs maps generate_series row g to
-- a unique key tuple.
INSERT INTO :"schema".:"source_table" (:pk_key, payload)
SELECT :pk_exprs,
       pg_catalog.md5(g::text)
FROM pg_catalog.generate_series(1, :rows) AS g;
