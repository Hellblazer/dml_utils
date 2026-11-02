package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.jooq.Table;
import org.jooq.TableField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKeyText.TEST_ORDERED_KEY_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKeyTimestamptz.TEST_ORDERED_KEY_TIMESTAMPTZ;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKeyUuid.TEST_ORDERED_KEY_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the ordered-key strategy end to end over the Liquibase fixtures: a
 * UUID primary key with a composite ordered index, a natural text key, a
 * timestamptz key and a mixed composite key. Covers single- and multi-threaded
 * runs, partial final chunks, empty and single-row tables, resume after a
 * failed chunk, and cancellation.
 */
class OrderedKeyIntegrationTest extends PostgresTestBase {

    private static final String APPEND_TEMPLATE =
            "UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x' WHERE <chunking_clause>";

    private static final String UUID_INDEX = "test_ordered_key_uuid_created_at_id_idx";
    private static final String TEXT_INDEX = "test_ordered_key_text_pkey";
    private static final String TIMESTAMPTZ_INDEX = "test_ordered_key_timestamptz_created_at_idx";
    private static final String MIXED_INDEX = "test_ordered_key_created_at_id_idx";

    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        for (Table<?> table : List.of(TEST_ORDERED_KEY_UUID, TEST_ORDERED_KEY_TEXT,
                TEST_ORDERED_KEY_TIMESTAMPTZ, TEST_ORDERED_KEY)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * A UUID primary key is chunked through its (created_at, id) index with
     * several workers, and every row is processed exactly once.
     */
    @Test
    void processesAUuidPrimaryKeyThroughItsCompositeIndex() {
        seedUuid(6);
        String label = uniqueLabel("ordered-uuid");

        run(label, TEST_ORDERED_KEY_UUID, UUID_INDEX, 2, 4, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY_UUID, TEST_ORDERED_KEY_UUID.PAYLOAD, 6);
        assertRunCounts(label, 3);
    }

    /**
     * A natural text primary key is itself a usable ordered key.
     */
    @Test
    void processesANaturalTextKey() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_ORDERED_KEY_TEXT, TEST_ORDERED_KEY_TEXT.NATURAL_KEY)
                    .values("k" + String.format("%02d", i))
                    .execute();
        }
        String label = uniqueLabel("ordered-text");

        run(label, TEST_ORDERED_KEY_TEXT, TEXT_INDEX, 2, 1, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY_TEXT, TEST_ORDERED_KEY_TEXT.PAYLOAD, 5);
        assertRunCounts(label, 3);
    }

    /**
     * A single-column timestamptz unique index is chunked by instant.
     */
    @Test
    void processesATimestamptzKey() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_ORDERED_KEY_TIMESTAMPTZ,
                            TEST_ORDERED_KEY_TIMESTAMPTZ.ID, TEST_ORDERED_KEY_TIMESTAMPTZ.CREATED_AT)
                    .values((long) i, timestamp(i))
                    .execute();
        }
        String label = uniqueLabel("ordered-timestamptz");

        run(label, TEST_ORDERED_KEY_TIMESTAMPTZ, TIMESTAMPTZ_INDEX, 2, 2, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY_TIMESTAMPTZ,
                TEST_ORDERED_KEY_TIMESTAMPTZ.PAYLOAD, 5);
        assertRunCounts(label, 3);
    }

    /**
     * The mixed (timestamptz, bigint) composite key over the existing fixture
     * processes every row exactly once, including the partial final chunk.
     */
    @Test
    void processesAMixedCompositeKeyWithAPartialFinalChunk() {
        seedMixed(5);
        String label = uniqueLabel("ordered-mixed");

        run(label, TEST_ORDERED_KEY, MIXED_INDEX, 2, 2, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY, TEST_ORDERED_KEY.PAYLOAD, 5);
        assertRunCounts(label, 3);
    }

    /**
     * A single-row table is one final chunk and the row is processed once.
     */
    @Test
    void processesASingleRowTable() {
        seedUuid(1);
        String label = uniqueLabel("ordered-single");

        run(label, TEST_ORDERED_KEY_UUID, UUID_INDEX, 10, 1, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY_UUID, TEST_ORDERED_KEY_UUID.PAYLOAD, 1);
        assertRunCounts(label, 1);
    }

    /**
     * An empty table creates a completed run with no chunks and writes nothing.
     */
    @Test
    void completesAnEmptyTableWithoutChunks() {
        String label = uniqueLabel("ordered-empty");

        run(label, TEST_ORDERED_KEY_UUID, UUID_INDEX, 10, 1, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY_UUID, TEST_ORDERED_KEY_UUID.PAYLOAD, 0);
        assertRunCounts(label, 0);
    }

    /**
     * A failed chunk leaves the run resumable; the retry uses the stored index
     * even when the caller omits it, and every row ends up processed once.
     */
    @Test
    void resumesAfterAFailedChunkWithTheStoredIndex() {
        for (int i = 1; i <= 6; i++) {
            dsl.insertInto(TEST_ORDERED_KEY_TEXT, TEST_ORDERED_KEY_TEXT.NATURAL_KEY)
                    .values("k" + String.format("%02d", i))
                    .execute();
        }
        String label = uniqueLabel("ordered-resume");
        String failingTemplate = "UPDATE <driving_table> SET payload ="
                + " CASE WHEN natural_key = 'k03' THEN (ascii(natural_key) / 0)::text"
                + " ELSE coalesce(payload, '') || 'x' END"
                + " WHERE <chunking_clause>";

        assertSqlState("22012", () -> run(label, TEST_ORDERED_KEY_TEXT, TEXT_INDEX, 2, 1,
                failingTemplate));

        Routines.setMigrationRunSqlText(dsl.configuration(), label, APPEND_TEMPLATE);
        run(label, TEST_ORDERED_KEY_TEXT, null, 2, 1, APPEND_TEMPLATE);

        assertExactlyOnce(TEST_ORDERED_KEY_TEXT, TEST_ORDERED_KEY_TEXT.PAYLOAD, 6);
        assertRunCounts(label, 3);
    }

    /**
     * A cancelled run is terminal: a later call resolves the stored strategy and
     * index but processes no chunk.
     */
    @Test
    void doesNotProcessChunksForACancelledRun() {
        seedUuid(4);
        String label = uniqueLabel("ordered-cancelled");
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(),
                        TEST_ORDERED_KEY_UUID.getSchema().getName(),
                        TEST_ORDERED_KEY_UUID.getName(), label, APPEND_TEMPLATE,
                        2, 1, ChunkingStrategy.ordered_key, "t", 0, UUID_INDEX);
        Routines.cancelMigrationRun(dsl.configuration(), label);

        run(label, TEST_ORDERED_KEY_UUID, UUID_INDEX, 2, 1, APPEND_TEMPLATE);

        assertEquals(0, dsl.fetchCount(TEST_ORDERED_KEY_UUID,
                        TEST_ORDERED_KEY_UUID.PAYLOAD.eq("x")),
                "a cancelled run must not process a chunk");
        assertTrue(run(label).getCancelledAt() != null, "the run stays cancelled");
    }

    private void run(String label, Table<?> table, String index, int chunkSize, int threads,
                     String template) {
        Routines.runMigrationChunks(dsl.configuration(), template,
                table.getSchema().getName(), table.getName(), label, chunkSize, threads,
                "t", ChunkingStrategy.ordered_key, 1, 0, index);
    }

    private void seedUuid(int rows) {
        for (int i = 1; i <= rows; i++) {
            dsl.insertInto(TEST_ORDERED_KEY_UUID,
                            TEST_ORDERED_KEY_UUID.ID, TEST_ORDERED_KEY_UUID.CREATED_AT)
                    .values(uuid(i), timestamp((i + 1) / 2))
                    .execute();
        }
    }

    private void seedMixed(int rows) {
        for (int i = 1; i <= rows; i++) {
            dsl.insertInto(TEST_ORDERED_KEY,
                            TEST_ORDERED_KEY.ID, TEST_ORDERED_KEY.CREATED_AT,
                            TEST_ORDERED_KEY.EXTERNAL_ID, TEST_ORDERED_KEY.LABEL,
                            TEST_ORDERED_KEY.COLLATED_LABEL, TEST_ORDERED_KEY.NUMERIC_KEY)
                    .values((long) i, timestamp((i + 1) / 2), uuid(i), "k" + i, "k" + i,
                            BigDecimal.valueOf(i))
                    .execute();
        }
    }

    private void assertExactlyOnce(Table<?> table, TableField<?, String> payload, int expected) {
        assertEquals(expected, dsl.fetchCount(table, payload.eq("x")),
                "every row should be processed exactly once");
        assertEquals(0, dsl.fetchCount(table, payload.ne("x").or(payload.isNull())),
                "no row should be missed or processed twice (which would append another x)");
    }

    private void assertRunCounts(String label, long expectedChunks) {
        MigrationRunRecord run = run(label);
        assertEquals(expectedChunks, run.getTotalChunks().longValue(), "chunk count");
        assertEquals(expectedChunks, run.getTotalChunksCompleted().longValue(),
                "completed chunk count");
        assertTrue(runCompleted(run.getRunId()), "the run should complete");
    }

    private MigrationRunRecord run(String label) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne();
    }
}
