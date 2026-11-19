-- Bootstrap the role ddl_utils' grants target. The install changelog is applied
-- by the jOOQ codegen container as the image superuser, which can create the
-- role; the test harness pre-creates it as admin, so the guard makes this a
-- no-op there.
--
-- A role is a cluster-wide object while the Liquibase lock is per database, so
-- two installs running concurrently against different databases of one cluster
-- can both pass the NOT EXISTS. The loser then gets one of two errors depending
-- on whether the winner's CREATE was still uncommitted:
--
--   * 42710 duplicate_object - the sequential case, from CreateRole's pre-check;
--   * 23505 unique_violation - the race, from pg_authid's unique index when the
--     winner commits between the check and the insert.
--
-- Catch both: the role exists either way, which is all the install needs.
DO
$$
    BEGIN
        IF (SELECT rolsuper
            FROM pg_catalog.pg_roles
            WHERE rolname = current_user)
            AND NOT EXISTS (SELECT
                            FROM pg_catalog.pg_roles
                            WHERE rolname = 'ddl_utils_caller')
        THEN
            BEGIN
                EXECUTE 'CREATE ROLE ddl_utils_caller';
            EXCEPTION
                WHEN duplicate_object OR unique_violation THEN NULL;
            END;
        END IF;
    END
$$;
