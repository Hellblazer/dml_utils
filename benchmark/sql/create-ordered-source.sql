-- Deterministic ordered-key benchmark source table.
--
-- A UUIDv4-format primary key with a useful (created_at, id) unique index: the
-- shape the ordered_key strategy exists for. Run by benchmark/setup.sh with
-- :schema, :source_table, :rows and :index set. The :index variable must stay on
-- one line: a psql variable is only substituted when the name follows the colon
-- with no whitespace.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

DROP TABLE IF EXISTS :"schema".:"source_table";

CREATE TABLE :"schema".:"source_table"
(
    id         uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL,
    payload    text        NOT NULL
);

COMMENT ON TABLE :"schema".:"source_table" IS
    'Benchmark fixture: deterministic UUIDv4-keyed rows with a (created_at, id) order.';
COMMENT ON COLUMN :"schema".:"source_table".id IS
    'Deterministic UUIDv4-format primary key derived from the row number.';
COMMENT ON COLUMN :"schema".:"source_table".created_at IS
    'Deterministic timestamp; ten consecutive rows share one value, so id breaks ties.';
COMMENT ON COLUMN :"schema".:"source_table".payload IS
    'Deterministic payload derived from the row number.';

CREATE UNIQUE INDEX :"index"
    ON :"schema".:"source_table" (created_at, id);

COMMENT ON INDEX :"schema".:"index" IS
    'The ordered-key index: (created_at, id), with id as the tiebreaker.';

-- The UUID is deterministic: md5 of the row number with the version (4) and
-- variant (8) nibbles set, so a refresh reproduces the table exactly.
INSERT INTO :"schema".:"source_table" (id, created_at, payload)
SELECT pg_catalog.overlay(
               pg_catalog.overlay(pg_catalog.md5(g::text), '4', 13, 1),
               '8', 17, 1)::uuid,
       timestamptz '2020-01-01 00:00:00+00'
           + ((g - 1) / 10) * interval '1 second',
       pg_catalog.md5(g::text)
FROM pg_catalog.generate_series(1, :rows) AS g;
