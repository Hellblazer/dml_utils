package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.RoutineDocumentationContract;

/**
 * Verifies that every routine has a README section and that no README section
 * names a routine that no longer exists, inheriting the shared
 * {@link RoutineDocumentationContract}.
 */
class RoutineDocumentationTest extends RoutineDocumentationContract {

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
