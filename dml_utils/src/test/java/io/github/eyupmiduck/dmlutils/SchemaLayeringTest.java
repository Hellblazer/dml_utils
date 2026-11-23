package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.SchemaLayeringContract;

/**
 * Checks that no lower dml_utils schema references a higher one:
 * {@code dml_utils} -> {@code dml_utils_lib} -> {@code dml_utils_data}.
 */
class SchemaLayeringTest extends SchemaLayeringContract {

    /**
     * The project profile, whose managed schemas are already in dependency order.
     *
     * @return the dml_utils profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }
}
