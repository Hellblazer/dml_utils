package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.RoutineSignatureContract;

/**
 * Verifies that every routine name in the dml_utils schemas has a single
 * signature, inheriting the shared {@link RoutineSignatureContract}. A
 * parameter-type change in a {@code CREATE OR REPLACE} body creates a new
 * overload and leaves the old one installed; the contract fails the build before
 * such a stale signature can reach a release.
 */
class RoutineSignatureTest extends RoutineSignatureContract {

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
