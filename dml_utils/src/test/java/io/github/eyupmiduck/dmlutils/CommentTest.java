package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.CommentContract;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;

/**
 * Verifies that every schema, table, column, domain, type, routine and trigger
 * in the managed schemas has a non-blank comment, inheriting the shared
 * {@link CommentContract}.
 */
class CommentTest extends CommentContract {

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
