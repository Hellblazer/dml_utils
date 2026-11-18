-- Throwaway benchmark target table.
--
-- The default workload INSERTs each chunk's source rows here, so the source
-- table is never modified. The harness truncates it before every run. Run by
-- benchmark/setup.sh with :schema, :target_table and :pk_def set. The :pk_def
-- variable must stay on one line: a psql variable is only substituted when the
-- name follows the colon with no whitespace, so a formatter that splits it breaks
-- the script.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

COMMENT ON SCHEMA :"schema" IS
    'Benchmark fixture schema for the synthetic sweep.';

DROP TABLE IF EXISTS :"schema".:"target_table";

CREATE TABLE :"schema".:"target_table"
(
    :pk_def,
    payload text NOT NULL
);

COMMENT ON TABLE :"schema".:"target_table" IS
    'Benchmark fixture: throwaway destination the workload inserts into; truncated before each run.';
COMMENT ON COLUMN :"schema".:"target_table".payload IS
    'Copied from the source row payload.';
