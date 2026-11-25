package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.PlpgsqlCheckContract;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;

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
     * The dml_utils profile, so this contract uses the project's roles, image,
     * changelog and schemas rather than the library defaults.
     *
     * @return the dml_utils profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }
}
