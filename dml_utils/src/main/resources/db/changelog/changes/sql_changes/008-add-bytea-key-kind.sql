-- Adds the bytea array to the packed boundary key, so a primary or ordered key
-- of one to three columns may include a bytea column. The attribute goes last,
-- which keeps every stored boundary readable: a boundary written before this
-- change simply has no bytea array. The type is used by migration_run_leaf and
-- migration_boundary columns, and PostgreSQL allows adding an attribute to a
-- composite type those columns use.
-- SQLFluff parses a composite type's attributes as bare words, so the
-- layout:type:data_type alignment relaxation (see .sqlfluff) does not apply
-- here and IntelliJ's aligned columns would trip LT01.
-- noqa:disable=LT01
ALTER TYPE dml_utils_data.migration_key ADD ATTRIBUTE bytea_values bytea[];
-- noqa:enable=LT01

COMMENT ON TYPE dml_utils_data.migration_key IS
    'Packed boundary-key value for a chunk boundary: one position-aligned array '
        'per supported kind (bigint, text, uuid, timestamptz or bytea), so exactly '
        'one array element is non-NULL per index and an unused kind''s array is '
        'NULL. Index i holds the value of boundary-key column i when that column '
        'has the array''s kind.';

-- terminal_leaf_for_run gains an OUT column for the new attribute, and CREATE OR
-- REPLACE cannot change a function's result row, so a database upgraded from an
-- earlier release must lose the old function before its changeset (which runs
-- after this one) creates the new one. The drop leaves nothing to restore here:
-- 009 grants the new function to the caller again, and 999 grants it on a fresh
-- database, where this DROP finds nothing and does nothing.
DROP FUNCTION IF EXISTS dml_utils_lib.terminal_leaf_for_run(
    dml_utils_data.non_null_bigint
);
