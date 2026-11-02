package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ResolveMigrationRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.resolve_migration_run}: it creates a run for a
 * new label (populating its boundaries) and returns the effective inputs, reuses
 * an active run ignoring differing inputs, reports an already-completed run, and
 * rejects a thread count over {@code max_worker_processes}.
 */
class ResolveMigrationRunTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Resets the run metadata and the fixture table between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_ORDERED_KEY).execute();
    }

    /**
     * A new label creates a run, records its start, and returns the effective
     * inputs and resolved primary key.
     */
    @Test
    void createsANewRunAndReturnsTheEffectiveInputs() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-new");

        ResolveMigrationRun resolved = resolve(label, TEMPLATE, "t", 2, 1);

        assertNotNull(resolved.getORunId());
        assertNotEquals(Boolean.TRUE, resolved.getOAlreadyCompleted(), "a new run is not complete");
        assertEquals(TEMPLATE, resolved.getOEffectiveSqlText());
        assertEquals(schema(), resolved.getOEffectiveSchemaName());
        assertEquals(name(), resolved.getOEffectiveTableName());
        assertEquals("t", resolved.getOEffectiveAlias());
        assertEquals(1, resolved.getOEffectiveThreads());
        assertEquals(0, resolved.getOEffectiveSleepMs());
        assertEquals(ChunkingStrategy.primary_key, resolved.getOChunkBy());
        assertArrayEquals(new String[]{"id"}, resolved.getOKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, resolved.getOKeyKinds());
        assertTrue(runStarted(resolved.getORunId()), "the new run should record started_at");
    }

    /**
     * An active run for the label is reused, and its stored sql_text, alias and
     * threads win over the differing inputs.
     */
    @Test
    void reusesAnActiveRunIgnoringDifferingInputs() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-resume");

        ResolveMigrationRun first = resolve(label, TEMPLATE, "t", 2, 1);
        ResolveMigrationRun second = resolve(label,
                "UPDATE <driving_table> SET payload = 'y' WHERE <chunking_clause>", "other", 99, 2);

        assertEquals(first.getORunId(), second.getORunId(), "the active run should be reused");
        assertNotEquals(Boolean.TRUE, second.getOAlreadyCompleted());
        assertEquals(TEMPLATE, second.getOEffectiveSqlText(), "the stored sql_text wins");
        assertEquals("t", second.getOEffectiveAlias(), "the stored alias wins");
        assertEquals(1, second.getOEffectiveThreads(), "the stored threads win");
        assertEquals(0, second.getOEffectiveSleepMs(), "the stored sleep wins");
    }

    /**
     * A new run stores the requested sleep, and a resumed run returns the stored
     * value even when the input differs.
     */
    @Test
    void storesSleepAndResumesWithTheStoredValue() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-sleep");

        ResolveMigrationRun first = resolve(label, TEMPLATE, "t", 2, 1, 25);
        assertEquals(25, first.getOEffectiveSleepMs(), "the new run uses the input sleep");

        ResolveMigrationRun second = resolve(label, TEMPLATE, "t", 2, 2, 99);
        assertEquals(25, second.getOEffectiveSleepMs(), "the stored sleep wins on resume");
        assertEquals(25, dsl.select(MIGRATION_RUN.SLEEP_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(first.getORunId()))
                .fetchOne(MIGRATION_RUN.SLEEP_MS), "the stored sleep is persisted");
    }

    /**
     * An active run keeps the chunking strategy it was created with; a later
     * call with a different strategy is ignored (the strategy is immutable for
     * the boundaries already computed).
     */
    @Test
    void reusesTheStoredChunkingStrategy() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-chunk-by");

        ResolveMigrationRun first = resolve(label, TEMPLATE, "t", 2, 1);
        ResolveMigrationRun second = resolveWithChunkBy(label, TEMPLATE, "t", 2, 1,
                ChunkingStrategy.blocks, 0);

        assertEquals(first.getORunId(), second.getORunId(), "the active run should be reused");
        assertEquals(ChunkingStrategy.primary_key, second.getOChunkBy(),
                "the stored strategy wins over a differing input");
        assertArrayEquals(new String[]{"id"}, second.getOKeyColumns(),
                "the stored strategy keeps the primary-key columns");
        assertArrayEquals(new String[]{"bigint"}, second.getOKeyKinds());
    }

    /**
     * A NULL chunking strategy is rejected before a run is created.
     */
    @Test
    void rejectsANullChunkingStrategy() {
        seedBigint(1, 2, 3);

        assertSqlState("22023", () -> Routines.resolveMigrationRun(
                dsl.configuration(), TEMPLATE, schema(), name(),
                uniqueLabel("resolve-chunk-by-null"), 2, 1, "t", null, 0, null));
    }

    /**
     * A negative sleep is rejected before it can be stored.
     */
    @Test
    void rejectsANegativeSleep() {
        assertDomainViolation(() -> resolve(uniqueLabel("resolve-sleep-negative"),
                TEMPLATE, "t", 2, 1, -1));
    }

    /**
     * A NULL sleep is rejected before a run is created.
     */
    @Test
    void rejectsANullSleep() {
        assertDomainViolation(() -> Routines.resolveMigrationRun(
                dsl.configuration(), TEMPLATE, schema(), name(),
                uniqueLabel("resolve-sleep-null"), 2, 1, "t",
                ChunkingStrategy.primary_key, null, null));
    }

    /**
     * A completed run is reported as already complete.
     */
    @Test
    void reportsAnAlreadyCompletedRun() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-complete");
        ResolveMigrationRun first = resolve(label, TEMPLATE, "t", 2, 1);

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(first.getORunId()))
                .execute();

        ResolveMigrationRun again = resolve(label, TEMPLATE, "t", 2, 1);
        assertTrue(again.getOAlreadyCompleted(), "a completed run should be reported complete");
    }

    /**
     * A boundary-population worker failure is surfaced to the caller and leaves
     * no active run behind.
     */
    @Test
    void propagatesANewRunBoundaryFailure() {
        String label = uniqueLabel("resolve-missing-table");

        assertSqlState("42P01", () -> Routines.resolveMigrationRun(
                dsl.configuration(), TEMPLATE, schema(), "does_not_exist",
                label, 2, 1, "t", ChunkingStrategy.primary_key, 0, null));

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "a failed worker transaction must not leave a run row");
    }

    /**
     * A thread count above {@code max_worker_processes} is rejected with
     * {@code 22023} for a new run.
     */
    @Test
    void rejectsThreadsOverMaxWorkerProcesses() {
        int tooMany = dsl.fetchOne("SELECT current_setting('max_worker_processes')::int")
                .get(0, Integer.class) + 1;

        assertSqlState("22023", () -> resolve(uniqueLabel("resolve-threads"), TEMPLATE, "t", 2, tooMany));
    }

    /**
     * An ordered_key run resolves the named index and returns the descriptor
     * stored on the run.
     */
    @Test
    void resolvesAnOrderedKeyRunAndStoresTheDescriptor() {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered");

        ResolveMigrationRun resolved = resolveOrderedKey(label,
                "test_ordered_key_created_at_id_idx");

        assertEquals(ChunkingStrategy.ordered_key, resolved.getOChunkBy());
        assertArrayEquals(new String[]{"created_at", "id"}, resolved.getOKeyColumns());
        assertArrayEquals(new String[]{"timestamptz", "bigint"}, resolved.getOKeyKinds());
        assertEquals("test_ordered_key_created_at_id_idx", storedOrderedKeyIndex(resolved));
    }

    /**
     * A resumed ordered_key run keeps the stored index and descriptor even when
     * the call names a different index.
     */
    @Test
    void resumeUsesTheStoredOrderedKeyDescriptor() {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered-resume");

        ResolveMigrationRun first = resolveOrderedKey(label,
                "test_ordered_key_created_at_id_idx");
        ResolveMigrationRun second = resolveOrderedKey(label,
                "test_ordered_key_external_id_idx");

        assertEquals(first.getORunId(), second.getORunId(), "the active run should be reused");
        assertEquals(ChunkingStrategy.ordered_key, second.getOChunkBy());
        assertArrayEquals(new String[]{"created_at", "id"}, second.getOKeyColumns(),
                "the stored descriptor wins");
        assertArrayEquals(new String[]{"timestamptz", "bigint"}, second.getOKeyKinds());
        assertEquals("test_ordered_key_created_at_id_idx", storedOrderedKeyIndex(second));
    }

    /**
     * A resumed ordered_key run fails closed with {@code 55000} when the live
     * index no longer resolves to the descriptor stored with the run.
     */
    @Test
    void resumeFailsWhenTheStoredDescriptorNoLongerMatchesTheIndex() throws Exception {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered-changed");
        ResolveMigrationRun first = resolveOrderedKey(label,
                "test_ordered_key_created_at_id_idx");

        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE dml_utils_data.migration_run"
                    + " SET ordered_key_columns = ARRAY['id']::name[],"
                    + " ordered_key_kinds = ARRAY['bigint']::text[]"
                    + " WHERE run_id = " + first.getORunId());
        }

        assertSqlState("55000", () -> resolveOrderedKey(label,
                "test_ordered_key_created_at_id_idx"));
    }

    /**
     * A resumed ordered run fails closed with {@code 55000} when the stored
     * index was dropped.
     */
    @Test
    void resumeFailsWhenTheStoredIndexWasDropped() throws Exception {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered-dropped");
        resolveOrderedKey(label, "test_ordered_key_created_at_id_idx");

        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX dml_utils_fixtures.test_ordered_key_created_at_id_idx");
            assertSqlState("55000", () -> resolveOrderedKey(label,
                    "test_ordered_key_created_at_id_idx"));
        } finally {
            recreateOrderedKeyIndex();
        }
    }

    /**
     * A resumed ordered run fails closed with {@code 55000} when the index still
     * exists but its key order changed.
     */
    @Test
    void resumeFailsWhenTheIndexOrderChanged() throws Exception {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered-reordered");
        resolveOrderedKey(label, "test_ordered_key_created_at_id_idx");

        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX dml_utils_fixtures.test_ordered_key_created_at_id_idx");
            statement.execute("CREATE UNIQUE INDEX test_ordered_key_created_at_id_idx"
                    + " ON dml_utils_fixtures.test_ordered_key (id, created_at)");
            assertSqlState("55000", () -> resolveOrderedKey(label,
                    "test_ordered_key_created_at_id_idx"));
        } finally {
            recreateOrderedKeyIndex();
        }
    }

    /**
     * A resumed ordered run fails closed with {@code 55000} when the stored
     * index is recreated as non-unique (resolver {@code 0A000}).
     */
    @Test
    void resumeFailsWhenTheStoredIndexIsNoLongerUnique() throws Exception {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered-nonunique");
        resolveOrderedKey(label, "test_ordered_key_created_at_id_idx");

        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX dml_utils_fixtures.test_ordered_key_created_at_id_idx");
            statement.execute("CREATE INDEX test_ordered_key_created_at_id_idx"
                    + " ON dml_utils_fixtures.test_ordered_key (created_at, id)");
            assertSqlState("55000", () -> resolveOrderedKey(label,
                    "test_ordered_key_created_at_id_idx"));
        } finally {
            recreateOrderedKeyIndex();
        }
    }

    /**
     * A resumed ordered run fails closed with {@code 55000} when the stored
     * index is recreated with too many key columns (resolver {@code 22023}).
     */
    @Test
    void resumeFailsWhenTheStoredIndexHasTooManyColumns() throws Exception {
        seedOrderedKey();
        String label = uniqueLabel("resolve-ordered-four-columns");
        resolveOrderedKey(label, "test_ordered_key_created_at_id_idx");

        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX dml_utils_fixtures.test_ordered_key_created_at_id_idx");
            statement.execute("CREATE UNIQUE INDEX test_ordered_key_created_at_id_idx"
                    + " ON dml_utils_fixtures.test_ordered_key"
                    + " (created_at, id, external_id, label)");
            assertSqlState("55000", () -> resolveOrderedKey(label,
                    "test_ordered_key_created_at_id_idx"));
        } finally {
            recreateOrderedKeyIndex();
        }
    }

    private void recreateOrderedKeyIndex() throws Exception {
        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS"
                    + " dml_utils_fixtures.test_ordered_key_created_at_id_idx");
            statement.execute("CREATE UNIQUE INDEX test_ordered_key_created_at_id_idx"
                    + " ON dml_utils_fixtures.test_ordered_key (created_at, id)");
        }
    }

    /**
     * ordered_key without an index is rejected before a run is created.
     */
    @Test
    void rejectsOrderedKeyWithoutAnIndex() {
        assertSqlState("22023", () -> Routines.resolveMigrationRun(
                dsl.configuration(), TEMPLATE,
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(),
                uniqueLabel("resolve-ordered-null"), 2, 1, "t",
                ChunkingStrategy.ordered_key, 0, null));
    }

    /**
     * A non-ordered strategy with an index is rejected before a run is created.
     */
    @Test
    void rejectsAnIndexForANonOrderedStrategy() {
        assertSqlState("22023", () -> Routines.resolveMigrationRun(
                dsl.configuration(), TEMPLATE, schema(), name(),
                uniqueLabel("resolve-primary-index"), 2, 1, "t",
                ChunkingStrategy.primary_key, 0, "test_bigint_pkey"));
    }

    /**
     * Seeds the ordered-key fixture with five rows whose timestamps repeat, so
     * the boundary order falls through to the id tiebreaker.
     */
    private ResolveMigrationRun resolveOrderedKey(String label, String index) {
        return Routines.resolveMigrationRun(dsl.configuration(), TEMPLATE,
                TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(),
                label, 2, 1, "t", ChunkingStrategy.ordered_key, 0, index);
    }

    private String storedOrderedKeyIndex(ResolveMigrationRun resolved) {
        return dsl.select(MIGRATION_RUN.ORDERED_KEY_INDEX)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(resolved.getORunId()))
                .fetchOne(MIGRATION_RUN.ORDERED_KEY_INDEX);
    }

    private ResolveMigrationRun resolve(String label, String sqlText, String alias, int chunkSize, int threads) {
        return resolve(label, sqlText, alias, chunkSize, threads, 0);
    }

    private ResolveMigrationRun resolve(String label, String sqlText, String alias, int chunkSize,
                                        int threads, int sleepMs) {
        return resolveWithChunkBy(label, sqlText, alias, chunkSize, threads, ChunkingStrategy.primary_key,
                sleepMs);
    }

    private ResolveMigrationRun resolveWithChunkBy(String label, String sqlText, String alias,
                                                   int chunkSize, int threads, ChunkingStrategy chunkBy,
                                                   int sleepMs) {
        return Routines.resolveMigrationRun(dsl.configuration(), sqlText, schema(), name(),
                label, chunkSize, threads, alias, chunkBy, sleepMs, null);
    }
}
