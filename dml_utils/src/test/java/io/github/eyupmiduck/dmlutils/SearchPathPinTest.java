package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the catalog-inspecting library routines pin
 * {@code search_path} to {@code pg_catalog}, so a caller's path cannot change
 * which object an (accidentally) unqualified reference resolves to.
 */
class SearchPathPinTest extends PostgresTestBase {

    private static final List<String> PINNED = List.of(
            "assert_partitioned_table",
            "ordered_key_attributes_for_index");

    /**
     * Both routines carry the pinned search path in {@code pg_proc.proconfig}.
     */
    @Test
    void catalogRoutinesPinTheSearchPath() {
        for (String name : PINNED) {
            String config = dsl.fetchOne("""
                    SELECT coalesce(pg_catalog.array_to_string(p.proconfig, ','), '')
                    FROM pg_catalog.pg_proc AS p
                             JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'dml_utils_lib'
                      AND p.proname = ?
                    """, name).get(0, String.class);
            assertTrue(config.contains("search_path=pg_catalog"),
                    name + " must pin search_path=pg_catalog, got: " + config);
        }
    }
}
