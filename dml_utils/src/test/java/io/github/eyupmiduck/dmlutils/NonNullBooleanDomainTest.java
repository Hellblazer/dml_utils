package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the {@code dml_utils_data.non_null_boolean} domain: it accepts both
 * boolean values, rejects NULL, and is used by the partition-wise flag argument
 * so an omitted flag fails fast.
 */
class NonNullBooleanDomainTest extends PostgresTestBase {

    /**
     * The domain accepts true and false.
     */
    @Test
    void acceptsBothBooleanValues() {
        assertEquals(Boolean.TRUE,
                evaluate("TRUE::dml_utils_data.non_null_boolean", Boolean.class));
        assertEquals(Boolean.FALSE,
                evaluate("FALSE::dml_utils_data.non_null_boolean", Boolean.class));
    }

    /**
     * The domain rejects a NULL value with a domain check violation.
     */
    @Test
    void rejectsNull() {
        assertDomainViolation(
                () -> evaluate("NULL::dml_utils_data.non_null_boolean", Boolean.class));
    }

    /**
     * The partition-wise function argument is domain-typed, so passing NULL fails
     * before the function body runs.
     */
    @Test
    void rejectsANullPartitionWiseArgument() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), "dml_utils_fixtures", "test_bigint",
                uniqueLabel("null-partition-wise"), "SELECT 1", 1, 1,
                ChunkingStrategy.primary_key, "t", 0, null, null));
    }
}
