package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@code dml_utils_lib.relation_filepath} and
 * {@code dml_utils_lib.assert_relation_filepath}: the physical filepath is read
 * from the catalog, a matching expectation passes, a changed one fails closed,
 * a NULL expectation is a no-op and a missing table is rejected.
 */
class RelationFilepathTest extends PostgresTestBase {

    /**
     * The filepath of a real table is non-empty.
     */
    @Test
    void returnsThePhysicalFilepath() {
        String path = Routines.relationFilepath(dsl.configuration(), schema(), name());

        assertNotNull(path);
        assertFalse(path.isEmpty(), "the filepath should not be empty");
    }

    /**
     * Asserting the current filepath passes.
     */
    @Test
    void passesForTheCurrentFilepath() {
        String path = Routines.relationFilepath(dsl.configuration(), schema(), name());

        Routines.assertRelationFilepath(dsl.configuration(), schema(), name(), path);
    }

    /**
     * Asserting a different filepath fails closed with {@code 22023}.
     */
    @Test
    void rejectsAStaleFilepath() {
        assertSqlState("22023", () -> Routines.assertRelationFilepath(dsl.configuration(),
                schema(), name(), "base/0/0"));
    }

    /**
     * A NULL expectation (a primary-key run) is a no-op.
     */
    @Test
    void isANoOpForANullFilepath() {
        Routines.assertRelationFilepath(dsl.configuration(), schema(), name(), null);
    }

    /**
     * A missing table is rejected with {@code 42P01}.
     */
    @Test
    void rejectsAMissingTable() {
        assertSqlState("42P01", () -> Routines.relationFilepath(dsl.configuration(),
                "dml_utils_fixtures", "no_such_table"));
    }
}
