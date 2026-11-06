-- Deterministic benchmark source table.
--
-- Created outside the Liquibase-managed schemas: it is a benchmark fixture, not
-- application schema, so it carries only the columns the workload reads. Run by
-- benchmark/setup.sh with :schema, :source_table, :rows, :pk_def, :pk_key and
-- :pk_exprs set. The primary key is one to three bigint columns, matching the
-- chunking engine. The :pk_def, :pk_key and :pk_exprs variables must each stay on
-- one line: a psql variable is only substituted when the name follows the colon
-- with no whitespace, so a formatter that splits them breaks the script.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

DROP TABLE IF EXISTS :"schema".:"source_table";

CREATE TABLE :"schema".:"source_table"
(
    :pk_def,
    payload text NOT NULL,
    PRIMARY KEY (:pk_key)
);

COMMENT ON TABLE :"schema".:"source_table" IS
    'Benchmark fixture: deterministic synthetic rows keyed by the configured bigint primary key.';
COMMENT ON COLUMN :"schema".:"source_table".payload IS
    'Deterministic payload derived from the row number.';

-- The row number always yields the same key tuple and payload, so refreshing the
-- database reproduces the table exactly. :pk_exprs maps generate_series row g to
-- a unique key tuple.
INSERT INTO :"schema".:"source_table" (:pk_key, payload)
SELECT :pk_exprs,
       pg_catalog.md5(g::text)
FROM pg_catalog.generate_series(1, :'rows'::bigint) AS g;
