package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.PlpgsqlCheckContract;

import java.util.Collection;

/**
 * Runs the {@code plpgsql_check} static analyser over every routine in the
 * {@code dml_utils}, {@code dml_utils_lib} and {@code dml_utils_data} schemas,
 * inheriting the shared {@link PlpgsqlCheckContract}.
 *
 * <p>The extension is compiled into the custom image and installed into the
 * template database, so each cloned test database (and the dev database, via
 * {@code docker/postgres/roles.sql}) has it.
 */
class PlpgsqlCheckTest extends PlpgsqlCheckContract {

    /**
     * The dml_utils container and changelog configuration.
     *
     * @return the configuration
     */
    @Override
    protected Configuration configuration() {
        return PostgresTestBase.CONFIGURATION;
    }

    /**
     * The schemas that hold the project's routines.
     *
     * @return the routine schemas
     */
    @Override
    protected Collection<String> routineSchemas() {
        return TestSchemas.APPLICATION;
    }
}
