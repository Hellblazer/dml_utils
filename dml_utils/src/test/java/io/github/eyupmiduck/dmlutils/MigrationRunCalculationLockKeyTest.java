package io.github.eyupmiduck.dmlutils;

import org.jooq.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.migration_run_calculation_lock_key}: the advisory
 * lock key is declared immutable, strict and parallel safe, rejects a NULL run
 * id through its domain, and is deterministic per run.
 */
class MigrationRunCalculationLockKeyTest extends PostgresTestBase {

    private static final String FUNCTION =
            "dml_utils_lib.migration_run_calculation_lock_key";

    /**
     * The catalog declaration matches the documented contract: immutable, strict
     * and parallel safe.
     */
    @Test
    void isImmutableStrictAndParallelSafe() {
        Record attributes = dsl.fetchOne("""
                SELECT p.provolatile, p.proisstrict, p.proparallel
                FROM pg_catalog.pg_proc AS p
                         JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname = 'dml_utils_lib'
                  AND p.proname = 'migration_run_calculation_lock_key'
                """);

        assertNotNull(attributes, "the lock-key routine must exist");
        assertEquals("i", attributes.get("provolatile", String.class),
                "the key derivation is immutable (bigint::text, not format())");
        assertTrue(attributes.get("proisstrict", Boolean.class),
                "STRICT would keep a NULL run id from collapsing onto a shared key");
        assertEquals("s", attributes.get("proparallel", String.class),
                "the key derivation is parallel safe");
    }

    /**
     * A NULL run id is rejected by the non-null domain at the call, so no caller
     * can reach the STRICT body with NULL.
     */
    @Test
    void rejectsANullRunId() {
        assertDomainViolation(() -> dsl.fetchOne("SELECT " + FUNCTION + "(NULL)"));
    }

    /**
     * The same run id always maps to the same non-NULL key, and different runs
     * map to different keys.
     */
    @Test
    void derivesAStableKeyPerRun() {
        Long first = dsl.fetchOne("SELECT " + FUNCTION + "(42)")
                .get(0, Long.class);
        Long again = dsl.fetchOne("SELECT " + FUNCTION + "(42)")
                .get(0, Long.class);
        Long other = dsl.fetchOne("SELECT " + FUNCTION + "(43)")
                .get(0, Long.class);

        assertNotNull(first, "a non-NULL run id yields a key");
        assertNotNull(again, "a non-NULL run id yields a key");
        assertNotNull(other, "a non-NULL run id yields a key");
        assertEquals(first, again, "the key is deterministic");
        assertNotEquals(first, other, "different runs use different keys");
    }
}
