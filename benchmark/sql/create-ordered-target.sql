-- Throwaway benchmark target for the ordered_key variant.
--
-- The default ordered workload INSERTs each chunk's source rows here, so the
-- source table is never modified. The harness truncates it before every run.
-- Run by benchmark/setup.sh with :schema and :target_table set.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

DROP TABLE IF EXISTS :"schema".:"target_table";

CREATE TABLE :"schema".:"target_table"
(
    id         uuid,
    created_at timestamptz,
    payload    text NOT NULL
);

COMMENT ON TABLE :"schema".:"target_table" IS
    'Benchmark fixture: throwaway destination for the ordered-key workload; truncated before each run.';
COMMENT ON COLUMN :"schema".:"target_table".id IS
    'Copied from the source UUID key.';
COMMENT ON COLUMN :"schema".:"target_table".created_at IS
    'Copied from the source timestamp.';
COMMENT ON COLUMN :"schema".:"target_table".payload IS
    'Copied from the source row payload.';
