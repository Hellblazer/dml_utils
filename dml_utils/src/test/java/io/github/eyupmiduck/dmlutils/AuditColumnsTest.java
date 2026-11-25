package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.AuditColumnsContract;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;

/**
 * Applies the shared {@link AuditColumnsContract} to the dml_utils application
 * schemas: every base table has {@code created_at}/{@code updated_at} as
 * {@code timestamptz NOT NULL DEFAULT now()} and an enabled {@code BEFORE
 * UPDATE} row trigger. The behavioral probe for {@code migration_run} lives in
 * {@link MigrationTablesTest}.
 */
class AuditColumnsTest extends AuditColumnsContract {

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
