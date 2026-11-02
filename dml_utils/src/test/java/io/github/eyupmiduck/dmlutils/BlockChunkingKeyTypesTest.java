package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the block (ctid) strategy does not depend on the primary-key type: a
 * read-only run completes over text-, uuid- and composite-keyed tables.
 */
class BlockChunkingKeyTypesTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "SELECT count(*) FROM <driving_table> WHERE <chunking_clause>";

    /**
     * Clears the run metadata and seeds one row in each fixture table.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.execute("TRUNCATE dml_utils_fixtures.test_text");
        dsl.execute("INSERT INTO dml_utils_fixtures.test_text (id, payload) VALUES ('k', 'p')");
        dsl.execute("TRUNCATE dml_utils_fixtures.test_uuid");
        dsl.execute("INSERT INTO dml_utils_fixtures.test_uuid (id, payload)"
                + " VALUES ('00000000-0000-0000-0000-000000000000', 'p')");
        dsl.execute("TRUNCATE dml_utils_fixtures.test_composite_mixed");
        dsl.execute("INSERT INTO dml_utils_fixtures.test_composite_mixed (a, b, payload)"
                + " VALUES (1, 'x', 'p')");
    }

    /**
     * A block run completes for a text, uuid and composite primary key.
     */
    @Test
    void blockChunkingIsIndependentOfTheKeyType() {
        for (String table : List.of("test_text", "test_uuid", "test_composite_mixed")) {
            String label = uniqueLabel("block-key");
            Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, "dml_utils_fixtures",
                    table, label, 1, 1, "t", ChunkingStrategy.blocks, 1, 0, null);

            assertTrue(runCompleted(label), table + " should complete with the block strategy");
        }
    }
}
