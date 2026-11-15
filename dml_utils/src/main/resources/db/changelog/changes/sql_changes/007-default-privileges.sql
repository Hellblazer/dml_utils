-- PostgreSQL grants EXECUTE on functions and USAGE on types to PUBLIC by
-- default. Revoke those hard-wired defaults for the owner role before any
-- routine or type is created (this changeset runs before 004/005), so a newly
-- added routine or explicitly created type never inherits a world-executable /
-- world-usable default even when it is omitted from 999-grant-privileges.
--
-- The revocation must be database-wide (no IN SCHEMA clause). A schema-scoped
-- default ACL is *added on top of* the hard-wired default, so it can only widen
-- access and a REVOKE there records nothing; only a global entry replaces the
-- hard-wired default. Table row types (the implicit composite types behind
-- migration_run and friends) ignore default ACLs entirely, so 999 still revokes
-- PUBLIC explicitly on those.
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner
    REVOKE EXECUTE ON FUNCTIONS FROM public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner
    REVOKE USAGE ON TYPES FROM public;
