package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.RollbackSignatureContract;

import java.util.Collection;
import java.util.List;

/**
 * Guards the routine rollback files, inheriting the shared
 * {@link RollbackSignatureContract}: every routine must have a
 * {@code DROP FUNCTION IF EXISTS <signature>} rollback that resolves to an
 * existing routine, and no signature may be dropped twice.
 *
 * <p>Because {@code IF EXISTS} makes a stale DROP a silent no-op, a signature
 * change that is not propagated to the rollback would otherwise go unnoticed
 * until a rollback left the routine behind. A procedure's rollback is a
 * {@code DROP PROCEDURE IF EXISTS} and lives beside the functions' rollbacks.
 */
class RollbackSignatureTest extends RollbackSignatureContract {

    /**
     * The rollback trees for the function changesets, the procedure changesets
     * and the schema changesets. The last is included because
     * {@code migration_key_is_canonical} is created by a schema changeset rather
     * than a routine changeset.
     *
     * @return the rollback directories
     */
    @Override
    protected Collection<String> rollbackDirectories() {
        return List.of("db/changelog/changes/functions-rollback",
                "db/changelog/changes/procedures-rollback",
                "db/changelog/changes/rollback");
    }

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
