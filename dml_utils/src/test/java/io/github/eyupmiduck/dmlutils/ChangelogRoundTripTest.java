package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogRoundTripContract;
import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the plain dml_utils changelog (with the staged ddl_utils changelog as
 * an installed baseline), rolls every dml changeset back, checks the dml schemas
 * are gone while the ddl_utils baseline survives, and re-applies, inheriting the
 * shared {@link ChangelogRoundTripContract}.
 *
 * <p>The install changelog cannot be used here: its role-bootstrap changeset has
 * no rollback. The baseline is applied outside the dml changelog's history.
 */
class ChangelogRoundTripTest extends ChangelogRoundTripContract {

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

    /**
     * The dml and fixture schemas are gone, while the installed ddl_utils
     * baseline remains.
     *
     * @param baseline   the baseline catalog
     * @param rolledBack the catalog after rollback
     */
    @Override
    protected void assertRolledBackBaseline(Map<String, String> baseline, Map<String, String> rolledBack) {
        super.assertRolledBackBaseline(baseline, rolledBack);
        assertTrue(rolledBack.containsKey("schema/ddl_utils"),
                "the ddl_utils baseline should survive the rollback");
        assertTrue(rolledBack.containsKey("schema/ddl_utils_lib"),
                "the ddl_utils_lib baseline should survive the rollback");
        assertFalse(rolledBack.containsKey("schema/dml_utils"),
                "dml_utils should be gone after rollback");
        assertFalse(rolledBack.containsKey("schema/dml_utils_lib"),
                "dml_utils_lib should be gone after rollback");
        assertFalse(rolledBack.containsKey("schema/dml_utils_data"),
                "dml_utils_data should be gone after rollback");
        assertFalse(rolledBack.containsKey("schema/dml_utils_fixtures"),
                "the fixture schema should be gone after rollback");
    }

    /**
     * Re-applying restores the dml and fixture schemas.
     *
     * @param applied   the catalog after the first application
     * @param reapplied the catalog after re-applying
     */
    @Override
    protected void assertReapplied(Map<String, String> applied, Map<String, String> reapplied) {
        super.assertReapplied(applied, reapplied);
        assertTrue(reapplied.containsKey("schema/dml_utils_fixtures"),
                "the fixture schema should exist after re-applying");
        assertTrue(reapplied.containsKey("schema/ddl_utils"),
                "the ddl_utils baseline should still exist after re-applying");
    }
}
