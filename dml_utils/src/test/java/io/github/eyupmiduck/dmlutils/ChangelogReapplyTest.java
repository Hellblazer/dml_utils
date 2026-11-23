package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogReapplyContract;
import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;

/**
 * Applies the plain dml_utils changelog and its fixtures twice and requires the
 * second run to be a no-op, inheriting the shared
 * {@link ChangelogReapplyContract}. The staged ddl_utils changelog is the
 * installed baseline and is not re-applied.
 */
class ChangelogReapplyTest extends ChangelogReapplyContract {

    /**
     * The plain production master, not the install changelog.
     *
     * @return the master resource
     */
    @Override
    protected String masterResource() {
        return ChangelogTestSupport.MASTER_RESOURCE;
    }

    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }
}
