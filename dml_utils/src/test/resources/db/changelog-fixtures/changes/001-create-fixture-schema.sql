-- The template database is created fresh on every test run, so this normally
-- creates the schema once. The fixture changelog is re-appliable after a lost
-- Liquibase history: this changeset has a precondition that marks it ran when the
-- schema already exists, 002's tables and indexes use IF NOT EXISTS, and 003's
-- grants are idempotent. It does not repair a fixture table that exists with a
-- different shape: drop the schema first in that case.
CREATE SCHEMA IF NOT EXISTS dml_utils_fixtures;

COMMENT ON SCHEMA dml_utils_fixtures IS
    'Test-only fixtures: tables the integration tests drive the migration routines over.';
