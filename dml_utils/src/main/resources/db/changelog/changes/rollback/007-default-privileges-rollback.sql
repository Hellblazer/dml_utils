-- Restore PostgreSQL's built-in default ACLs for future objects. The forward
-- changeset uses database-wide entries (no IN SCHEMA), so the inverse is
-- database-wide too.
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner
    GRANT EXECUTE ON FUNCTIONS TO public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner
    GRANT USAGE ON TYPES TO public;
