package io.github.eyupmiduck.dmlutils;

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
 * until a rollback left the routine behind.
 */
class RollbackSignatureTest extends RollbackSignatureContract {

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

    /**
     * The rollback trees for the function changesets and the schema changesets.
     * The latter is included because {@code migration_key_is_canonical} is
     * created by a schema changeset rather than a routine changeset.
     *
     * @return the rollback directories
     */
    @Override
    protected Collection<String> rollbackDirectories() {
        return List.of("db/changelog/changes/functions-rollback",
                "db/changelog/changes/rollback");
    }
}
