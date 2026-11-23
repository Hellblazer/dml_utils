package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogLayoutContract;
import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;

import java.nio.file.Path;

/**
 * Verifies the in-place changelog layout, inheriting the shared
 * {@link ChangelogLayoutContract}: a changeset's SQL and rollback paths match its
 * id, and a routine or trigger file declares the object its path names.
 */
class ChangelogLayoutTest extends ChangelogLayoutContract {

    /**
     * The plain production master. The profile's configuration points at the
     * install changelog, which lives outside the {@code db/changelog} tree and
     * bundles ddl_utils; the layout contract checks the changelog this project
     * owns.
     *
     * @return the production master
     */
    @Override
    protected Path master() {
        return ChangelogTestSupport.master();
    }

    /**
     * The dml_utils profile, so this contract uses the project's changelog
     * locations.
     *
     * @return the dml_utils profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }
}
