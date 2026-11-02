package io.github.eyupmiduck.dmlutils;

import java.util.List;

/**
 * Schema lists shared by tests that inspect every application routine.
 */
final class TestSchemas {

    /**
     * The schemas that hold dml_utils routines, in dependency order.
     */
    static final List<String> APPLICATION =
            List.of("dml_utils", "dml_utils_lib", "dml_utils_data");

    private TestSchemas() {
    }
}
