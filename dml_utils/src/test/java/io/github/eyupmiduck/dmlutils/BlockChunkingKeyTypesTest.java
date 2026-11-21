package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        dsl.truncate(TEST_TEXT).execute();
        dsl.insertInto(TEST_TEXT, TEST_TEXT.ID, TEST_TEXT.PAYLOAD)
                .values("k", "p")
                .execute();
        dsl.truncate(TEST_UUID).execute();
        dsl.insertInto(TEST_UUID, TEST_UUID.ID, TEST_UUID.PAYLOAD)
                .values(new UUID(0L, 0L), "p")
                .execute();
        dsl.truncate(TEST_COMPOSITE_MIXED).execute();
        dsl.insertInto(TEST_COMPOSITE_MIXED,
                        TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B, TEST_COMPOSITE_MIXED.PAYLOAD)
                .values(1L, "x", "p")
                .execute();
    }

    /**
     * A block run completes for a text, uuid and composite primary key.
     */
    @Test
    void blockChunkingIsIndependentOfTheKeyType() {
        for (String table : List.of("test_text", "test_uuid", "test_composite_mixed")) {
            String label = uniqueLabel("block-key");
            Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, "dml_utils_fixtures",
                    table, label, 1, 1, "t", ChunkingStrategy.blocks, 1, 0, null, false, 3, 1000);

            assertTrue(runCompleted(label), table + " should complete with the block strategy");
            MigrationRunRecord run = dsl.selectFrom(MIGRATION_RUN)
                    .where(MIGRATION_RUN.LABEL.eq(label))
                    .fetchOne();
            assertNotNull(run, table + " run row must exist");
            assertTrue(run.getTotalChunks() > 0,
                    table + " must produce at least one block chunk, not complete empty");
        }
    }
}
