package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.TrackingTablesContract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Verifies that Liquibase keeps its tracking tables out of the application
 * schemas: they live in the dedicated {@code liquibase} schema, prefixed with
 * {@code dml_utils_}, rather than as the generic {@code databasechangelog} /
 * {@code databasechangeloglock} tables. Inherits the shared
 * {@link TrackingTablesContract}.
 */
class LiquibaseTrackingTablesTest extends TrackingTablesContract {

    /**
     * The schemas that must not hold Liquibase's generic tracking-table names:
     * the application schemas plus the test-only fixture schema.
     *
     * @return the application schemas
     */
    @Override
    protected Collection<String> applicationSchemas() {
        List<String> schemas = new ArrayList<>(TestSchemas.APPLICATION);
        schemas.add(PUBLIC_SCHEMA);
        schemas.add("dml_utils_fixtures");
        return schemas;
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
