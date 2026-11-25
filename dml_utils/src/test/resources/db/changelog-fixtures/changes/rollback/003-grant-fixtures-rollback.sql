ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_fixtures
    REVOKE SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES FROM dml_utils_test;

REVOKE SELECT, INSERT, UPDATE, DELETE, TRUNCATE
    ON ALL TABLES IN SCHEMA dml_utils_fixtures FROM dml_utils_test;

REVOKE USAGE ON SCHEMA dml_utils_fixtures FROM dml_utils_test;
