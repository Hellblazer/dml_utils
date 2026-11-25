package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.RoutineFileContract;

/**
 * Checks that every function, procedure and trigger file in the dml_utils
 * changelog declares the object its path names, and that its rollback drops it.
 */
class RoutineFileTest extends RoutineFileContract {

    /**
     * The project profile, so the contract reads the dml_utils changes tree.
     *
     * @return the dml_utils profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }
}
