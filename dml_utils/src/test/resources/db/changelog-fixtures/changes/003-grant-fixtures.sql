GRANT USAGE ON SCHEMA dml_utils_fixtures TO dml_utils_test;

GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA dml_utils_fixtures TO dml_utils_test;

-- ALL TABLES is a snapshot of the relations that exist now. Tests create
-- partitions in this schema at runtime (as dml_utils_owner, the creator role), and
-- a later fixture changeset could add tables, so give the creator role's future
-- tables the same grant instead of relying on every caller to re-grant.
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_fixtures
    GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES TO dml_utils_test;
