package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestByteaCompositePk.TEST_BYTEA_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestByteaPartitioned.TEST_BYTEA_PARTITIONED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestByteaPk.TEST_BYTEA_PK;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies bytea as a primary-key and ordered-key column: boundaries cut by row
 * number over the key order agree with the rendered range predicate for keys
 * that include 0x00 and 0xff bytes and prefixes of different lengths, every row
 * is processed exactly once (also after a failed run is resumed), the rendered
 * literals do not depend on the session's string settings, the chunk predicate
 * is served by the primary-key index, and a bytea key works on a plain table, on
 * a function-over-table run and on a partitioned parent.
 */
class ByteaKeyTest extends PartitionTestBase {

    private static final String APPEND =
            "UPDATE <driving_table> SET payload = coalesce(payload, '') || 'x' WHERE <chunking_clause>";
    private static final String[] BYTEA = {"bytea"};
    private static final String[] TEXT_TEXT_BYTEA = {"text", "text", "bytea"};

    /**
     * The key {@code 00 ff} sorts inside the second chunk of the edge-key set at
     * chunk size 3, so a failure there leaves the first chunk completed.
     */
    private static final String FAIL_ON_00FF = "UPDATE <driving_table> SET payload = CASE"
            + " WHEN t.chash = pg_catalog.decode('00ff', 'hex')"
            + " THEN (pg_catalog.get_byte(t.chash, 0) / 0)::text ELSE 'x' END WHERE <chunking_clause>";

    /**
     * Keys that stress unsigned byte order: the empty key, 0x00 and 0xff bytes,
     * prefixes of different lengths, bytes either side of the sign bit, and the
     * backslash and quote bytes.
     */
    private static final List<byte[]> EDGE_KEYS = List.of(
            bytes(), bytes(0x00), bytes(0x00, 0x00), bytes(0x00, 0x01), bytes(0x00, 0xff),
            bytes(0x01), bytes(0x01, 0x00), bytes(0x27), bytes(0x5c), bytes(0x5c, 0x78),
            bytes(0x7f), bytes(0x80), bytes(0x80, 0x00), bytes(0xfe), bytes(0xff),
            bytes(0xff, 0x00), bytes(0xff, 0xff), bytes(0xff, 0xff, 0xff));

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    /**
     * Clears the fixtures and drops the helper function between tests.
     */
    @BeforeEach
    void reset() {
        for (Table<?> table : List.of(TEST_BYTEA_PK, TEST_BYTEA_COMPOSITE_PK, TEST_BYTEA_PARTITIONED)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * Drops the helper function a test may have created.
     */
    @AfterEach
    void dropHelperFunction() {
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_bytea(bytea)");
    }

    /**
     * A single-column bytea primary key processes every row exactly once at chunk
     * sizes that put boundaries on every kind of edge key.
     */
    @Test
    void processesASingleByteaKeyExactlyOnce() {
        for (int chunkSize : new int[]{1, 2, 3, 5, 7, EDGE_KEYS.size()}) {
            dsl.truncate(TEST_BYTEA_PK).execute();
            seedSingle(EDGE_KEYS);
            String label = uniqueLabel("bytea-single-" + chunkSize);

            runPrimaryKey(label, APPEND, TEST_BYTEA_PK, chunkSize, 1);

            assertEveryRowDoneOnce(TEST_BYTEA_PK, EDGE_KEYS.size(), "chunk size " + chunkSize);
            assertTrue(runCompleted(label), "the run completes at chunk size " + chunkSize);
        }
    }

    /**
     * A composite (text, text, bytea) primary key processes every row exactly
     * once, with chunk boundaries falling inside a text prefix.
     */
    @Test
    void processesACompositeTextTextByteaKeyExactlyOnce() {
        for (int chunkSize : new int[]{1, 4, 5, 18, 71}) {
            dsl.truncate(TEST_BYTEA_COMPOSITE_PK).execute();
            seedComposite(EDGE_KEYS);
            String label = uniqueLabel("bytea-composite-" + chunkSize);

            runPrimaryKey(label, APPEND, TEST_BYTEA_COMPOSITE_PK, chunkSize, 1);

            assertEveryRowDoneOnce(TEST_BYTEA_COMPOSITE_PK, 4 * EDGE_KEYS.size(),
                    "chunk size " + chunkSize);
            assertTrue(runCompleted(label), "the run completes at chunk size " + chunkSize);
        }
    }

    /**
     * Several workers over a realistic population of 32-byte hashes still process
     * each row exactly once.
     */
    @Test
    void processesRealisticHashKeysExactlyOnceWithSeveralWorkers() {
        seedHashes(3000);
        String label = uniqueLabel("bytea-hashes");

        runPrimaryKey(label, APPEND, TEST_BYTEA_COMPOSITE_PK, 250, 3);

        assertEveryRowDoneOnce(TEST_BYTEA_COMPOSITE_PK, 3000, "3000 hash keys");
        assertEquals(12L, totalChunks(runId(label)), "3000 rows at 250 a chunk");
    }

    /**
     * Boundaries are cut by row number over {@code ORDER BY} the key, and the
     * rendered range predicate must agree with that order: every row of a single
     * bytea key and of a composite key falls in exactly one rendered chunk.
     */
    @Test
    void everyRowLandsInExactlyOneRenderedChunk() {
        seedSingle(EDGE_KEYS);
        for (int chunkSize : new int[]{1, 2, 3, 4, 7}) {
            long runId = populate(TEST_BYTEA_PK, uniqueLabel("bytea-render-single"), chunkSize);

            List<String> rows = renderedChunkRows(runId, TEST_BYTEA_PK, "chash",
                    "pg_catalog.encode(t.chash, 'hex')", BYTEA, dsl);

            assertEquals(hexes(EDGE_KEYS), sorted(rows),
                    "single key, chunk size " + chunkSize + ": each key in exactly one chunk");
        }

        seedComposite(EDGE_KEYS);
        for (int chunkSize : new int[]{1, 3, 5, 11}) {
            long runId = populate(TEST_BYTEA_COMPOSITE_PK, uniqueLabel("bytea-render-composite"),
                    chunkSize);

            List<String> rows = renderedChunkRows(runId, TEST_BYTEA_COMPOSITE_PK,
                    "tenant_id, collection, chash", COMPOSITE_ROW, TEXT_TEXT_BYTEA, dsl);

            assertEquals(compositeRows(EDGE_KEYS), sorted(rows),
                    "composite key, chunk size " + chunkSize + ": each row in exactly one chunk");
        }
    }

    /**
     * The literal is {@code pg_catalog.decode('<hex>', 'hex')} and the boundary
     * text is plain hex, so neither depends on {@code bytea_output} or
     * {@code standard_conforming_strings}: a session with {@code escape} output
     * and string escapes enabled populates the same boundaries, renders the same
     * SQL, and every row still lands in exactly one chunk.
     */
    @Test
    void rendersTheSameSqlUnderAnySessionStringSettings() throws SQLException {
        seedSingle(EDGE_KEYS);
        seedComposite(EDGE_KEYS);
        long defaultRun = populate(TEST_BYTEA_COMPOSITE_PK, uniqueLabel("bytea-default"), 5);
        List<String> defaultSql = renderedChunkSql(defaultRun, TEST_BYTEA_COMPOSITE_PK,
                "tenant_id, collection, chash", COMPOSITE_ROW, TEXT_TEXT_BYTEA, dsl);

        try (Connection connection = openTestConnection()) {
            DSLContext hostile = DSL.using(connection, SQLDialect.POSTGRES);
            if (tryDisableStandardConformingStrings(hostile)) {
                hostile.execute("SET escape_string_warning = off");
            }
            hostile.execute("SET bytea_output = 'escape'");
            assertEquals("escape", hostile.fetchOne("SHOW bytea_output").get(0, String.class));

            long hostileRun = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                    .populateMigrationBoundaries(hostile.configuration(),
                            schema(TEST_BYTEA_COMPOSITE_PK), name(TEST_BYTEA_COMPOSITE_PK),
                            uniqueLabel("bytea-hostile"), APPEND, 5, 1,
                            ChunkingStrategy.primary_key, "t", 0, null, false);
            List<String> hostileSql = renderedChunkSql(hostileRun, TEST_BYTEA_COMPOSITE_PK,
                    "tenant_id, collection, chash", COMPOSITE_ROW, TEXT_TEXT_BYTEA, hostile);

            assertEquals(defaultSql, hostileSql, "the rendered chunk SQL is setting-independent");
            assertTrue(hostileSql.stream().anyMatch(sql -> sql.contains("pg_catalog.decode('00ff', 'hex')")),
                    "bytea literals are rendered through decode of hex digits");
            assertFalse(hostileSql.stream().anyMatch(sql -> sql.contains("\\x")),
                    "no backslash-hex literal is emitted");

            List<String> rows = renderedChunkRows(hostileRun, TEST_BYTEA_COMPOSITE_PK,
                    "tenant_id, collection, chash", COMPOSITE_ROW, TEXT_TEXT_BYTEA, hostile);
            assertEquals(compositeRows(EDGE_KEYS), sorted(rows),
                    "every row lands in exactly one chunk under the hostile settings");

            String[] hex = hostile.fetchOne(
                    "SELECT o_start_values FROM dml_utils_lib.migration_key_pair_values("
                            + "(SELECT boundary_id FROM dml_utils_data.migration_boundary"
                            + " WHERE run_id = ? AND boundary_no = 1),"
                            + " (SELECT boundary_id FROM dml_utils_data.migration_boundary"
                            + " WHERE run_id = ? AND boundary_no = 2), ARRAY['text','text','bytea'])",
                    hostileRun, hostileRun).get(0, String[].class);
            assertArrayEquals(new String[]{"t0", "a", "01"}, hex,
                    "the sixth key of the first prefix is the first boundary after the start, as bare hex");
        }
    }

    /**
     * A chunk predicate over a composite bytea key can be served by the
     * primary-key index, because the literals are constants the planner folds.
     * With sequential scans disabled the planner has no other access path, so
     * the plan does not depend on version-specific costing: it must carry an
     * Index Cond on the primary-key index, and the condition must name the bytea
     * column (a predicate the planner could not fold would be a Filter).
     */
    @Test
    void servesAChunkPredicateFromThePrimaryKeyIndex() throws SQLException {
        seedHashes(4000);
        executeAsOwner("ANALYZE " + PARTITION_SCHEMA + ".test_bytea_composite_pk");
        long runId = populate(TEST_BYTEA_COMPOSITE_PK, uniqueLabel("bytea-plan"), 100);
        List<String> sql = renderedChunkSql(runId, TEST_BYTEA_COMPOSITE_PK,
                "tenant_id, collection, chash", "1", TEXT_TEXT_BYTEA, dsl);

        try (Connection connection = openTestConnection()) {
            DSLContext planner = DSL.using(connection, SQLDialect.POSTGRES);
            planner.execute("SET enable_seqscan = off");
            String plan = planner.fetchOne("EXPLAIN (FORMAT JSON) " + sql.get(10))
                    .get(0, String.class);

            assertTrue(plan.contains("test_bytea_composite_pk_pkey"),
                    "the plan reads the primary-key index: " + plan);
            assertTrue(plan.contains("Index Cond"), "the key predicate is an index condition: " + plan);
            assertTrue(plan.contains("chash"), "the index condition covers the bytea column: " + plan);
        }
    }

    /**
     * A failed bytea run resumes under the same label: the chunks completed
     * before the failure are not processed again and the rest are processed once.
     */
    @Test
    void resumesASingleByteaKeyRunExactlyOnce() {
        seedSingle(EDGE_KEYS);
        String label = uniqueLabel("bytea-resume");

        assertSqlState("22012", () -> runPrimaryKey(label, FAIL_ON_00FF, TEST_BYTEA_PK, 3, 1));
        long runId = runId(label);
        assertTrue(completedBoundaries(runId) >= 1, "a chunk completed before the failure");
        assertTrue(completedBoundaries(runId) < totalChunks(runId), "the run stopped part way");
        assertFalse(runCompleted(runId), "the run is not complete");
        assertEquals(3, payloadCount(TEST_BYTEA_PK, "x"), "only the first chunk was processed");

        Routines.setMigrationRunSqlText(dsl.configuration(), label, APPEND);
        runPrimaryKey(label, APPEND, TEST_BYTEA_PK, 3, 1);

        assertEveryRowDoneOnce(TEST_BYTEA_PK, EDGE_KEYS.size(), "after the resume");
        assertTrue(runCompleted(runId), "the resumed run completes");
        assertEquals(totalChunks(runId), totalChunksCompleted(runId));
    }

    /**
     * A failed composite (text, text, bytea) run resumes the same way.
     */
    @Test
    void resumesACompositeByteaKeyRunExactlyOnce() {
        seedComposite(EDGE_KEYS);
        String label = uniqueLabel("bytea-composite-resume");
        String failing = "UPDATE <driving_table> SET payload = CASE"
                + " WHEN t.tenant_id = 't1' AND t.collection = 'b'"
                + " AND t.chash = pg_catalog.decode('00ff', 'hex')"
                + " THEN (pg_catalog.get_byte(t.chash, 0) / 0)::text ELSE 'x' END WHERE <chunking_clause>";

        assertSqlState("22012", () -> runPrimaryKey(label, failing, TEST_BYTEA_COMPOSITE_PK, 5, 1));
        long runId = runId(label);
        assertTrue(completedBoundaries(runId) >= 1, "a chunk completed before the failure");
        assertFalse(runCompleted(runId), "the run is not complete");

        Routines.setMigrationRunSqlText(dsl.configuration(), label, APPEND);
        runPrimaryKey(label, APPEND, TEST_BYTEA_COMPOSITE_PK, 5, 1);

        assertEveryRowDoneOnce(TEST_BYTEA_COMPOSITE_PK, 4 * EDGE_KEYS.size(), "after the resume");
        assertTrue(runCompleted(runId), "the resumed run completes");
    }

    /**
     * An ordered_key run over a bytea unique index records the bytea kind and
     * processes every row once.
     */
    @Test
    void runsAnOrderedKeyOverAByteaIndex() {
        seedSingle(EDGE_KEYS);
        String label = uniqueLabel("bytea-ordered");

        Routines.runMigrationChunks(dsl.configuration(), APPEND, schema(TEST_BYTEA_PK),
                name(TEST_BYTEA_PK), label, 4, 1, "t", ChunkingStrategy.ordered_key, 1, 0,
                "test_bytea_pk_pkey", false);

        assertEveryRowDoneOnce(TEST_BYTEA_PK, EDGE_KEYS.size(), "ordered-key run");
        assertTrue(runCompleted(label), "the ordered-key run completes");
        assertArrayEquals(new String[]{"bytea"}, dsl.select(MIGRATION_RUN.ORDERED_KEY_KINDS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.ORDERED_KEY_KINDS), "the run stores the bytea kind");
    }

    /**
     * A function taking the key's bytea type runs over a bytea primary key,
     * because the resolver exposes the column's real type.
     */
    @Test
    void appliesAFunctionOverAByteaPrimaryKey() {
        dsl.execute("CREATE OR REPLACE FUNCTION public.mark_bytea(c bytea)"
                + " RETURNS void LANGUAGE sql AS $$"
                + " UPDATE dml_utils_fixtures.test_bytea_pk"
                + " SET payload = coalesce(payload, '') || 'x' WHERE chash = c $$");
        seedSingle(EDGE_KEYS);
        String label = uniqueLabel("fn-bytea");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_BYTEA_PK), name(TEST_BYTEA_PK),
                "public", "mark_bytea", 4, 1, label, null, ChunkingStrategy.primary_key, 1, 0);

        assertTrue(runCompleted(label), "the run completes");
        assertEveryRowDoneOnce(TEST_BYTEA_PK, EDGE_KEYS.size(), "function over table");
    }

    /**
     * A partitioned parent whose primary key is a (text, text, bytea) key runs as
     * one relation, like any other table, with the key on the parent.
     */
    @Test
    void runsOverAPartitionedParentWithoutPartitionWise() {
        seedPartitioned(EDGE_KEYS);
        String label = uniqueLabel("bytea-partitioned");

        Routines.runMigrationChunks(dsl.configuration(), APPEND, schema(TEST_BYTEA_PARTITIONED),
                name(TEST_BYTEA_PARTITIONED), label, 7, 2, "t", ChunkingStrategy.primary_key, 1, 0,
                null, false);

        assertEveryRowDoneOnce(TEST_BYTEA_PARTITIONED, 2 * EDGE_KEYS.size(), "partitioned parent");
        assertTrue(runCompleted(label), "the run completes");
        assertEquals(0, dsl.fetchCount(MIGRATION_RUN_LEAF,
                        MIGRATION_RUN_LEAF.RUN_ID.eq(runId(label))),
                "a run over the parent as one relation has no leaf manifest");
    }

    /**
     * A partition-wise run chunks each leaf of the same parent by its bytea key
     * and processes every row once.
     */
    @Test
    void runsOverAPartitionedParentPartitionWise() {
        seedPartitioned(EDGE_KEYS);
        String label = uniqueLabel("bytea-partition-wise");

        Routines.runMigrationChunks(dsl.configuration(), APPEND, schema(TEST_BYTEA_PARTITIONED),
                name(TEST_BYTEA_PARTITIONED), label, 7, 2, "t", ChunkingStrategy.primary_key, 1, 0,
                null, true);

        assertEveryRowDoneOnce(TEST_BYTEA_PARTITIONED, 2 * EDGE_KEYS.size(), "partition-wise");
        assertTrue(runCompleted(label), "the partition-wise run completes");
        long runId = runId(label);
        assertTrue(dsl.select(MIGRATION_RUN.PARTITION_WISE).from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.RUN_ID.eq(runId)).fetchOne(MIGRATION_RUN.PARTITION_WISE),
                "the run is recorded as partition-wise, not silently run as one relation");
        assertEquals(2, dsl.fetchCount(MIGRATION_RUN_LEAF, MIGRATION_RUN_LEAF.RUN_ID.eq(runId)),
                "one manifest leaf per partition");
    }

    private static final String COMPOSITE_ROW =
            "t.tenant_id || '/' || t.collection || '/' || pg_catalog.encode(t.chash, 'hex')";

    private void runPrimaryKey(String label, String template, Table<?> table, int chunkSize,
                               int threads) {
        Routines.runMigrationChunks(dsl.configuration(), template, schema(table), name(table), label,
                chunkSize, threads, "t", ChunkingStrategy.primary_key, 1, 0, null, false);
    }

    private long populate(Table<?> table, String label, int chunkSize) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(table), name(table), label, APPEND, chunkSize, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false);
    }

    private void seedSingle(List<byte[]> keys) {
        for (byte[] key : keys) {
            dsl.insertInto(TEST_BYTEA_PK, TEST_BYTEA_PK.CHASH).values(key).execute();
        }
    }

    private void seedComposite(List<byte[]> keys) {
        for (String tenant : List.of("t0", "t1")) {
            for (String collection : List.of("a", "b")) {
                for (byte[] key : keys) {
                    dsl.insertInto(TEST_BYTEA_COMPOSITE_PK, TEST_BYTEA_COMPOSITE_PK.TENANT_ID,
                                    TEST_BYTEA_COMPOSITE_PK.COLLECTION, TEST_BYTEA_COMPOSITE_PK.CHASH)
                            .values(tenant, collection, key)
                            .execute();
                }
            }
        }
    }

    private void seedPartitioned(List<byte[]> keys) {
        for (String collection : List.of("alpha", "beta")) {
            for (byte[] key : keys) {
                dsl.insertInto(TEST_BYTEA_PARTITIONED, TEST_BYTEA_PARTITIONED.TENANT_ID,
                                TEST_BYTEA_PARTITIONED.COLLECTION, TEST_BYTEA_PARTITIONED.CHASH)
                        .values("t0", collection, key)
                        .execute();
            }
        }
    }

    /**
     * Seeds 32-byte hash keys across three tenants and two collections.
     */
    private void seedHashes(int count) {
        dsl.execute("INSERT INTO dml_utils_fixtures.test_bytea_composite_pk (tenant_id, collection, chash)"
                + " SELECT 't' || (g % 3), 'c' || (g % 2),"
                + " pg_catalog.decode(pg_catalog.md5(g::text) || pg_catalog.md5(g::text || 'x'), 'hex')"
                + " FROM pg_catalog.generate_series(1, ?) AS g", count);
    }

    private void assertEveryRowDoneOnce(Table<?> table, int expectedRows, String context) {
        assertEquals(expectedRows, dsl.fetchCount(table), context + ": row count");
        assertEquals(expectedRows, payloadCount(table, "x"),
                context + ": every row processed exactly once (a repeat would hold xx)");
    }

    private static List<String> hexes(List<byte[]> keys) {
        List<String> result = new ArrayList<>();
        for (byte[] key : keys) {
            result.add(hex(key));
        }
        return sorted(result);
    }

    private static List<String> compositeRows(List<byte[]> keys) {
        List<String> result = new ArrayList<>();
        for (String tenant : List.of("t0", "t1")) {
            for (String collection : List.of("a", "b")) {
                for (byte[] key : keys) {
                    result.add(tenant + "/" + collection + "/" + hex(key));
                }
            }
        }
        return sorted(result);
    }

    private static String hex(byte[] key) {
        StringBuilder text = new StringBuilder();
        for (byte value : key) {
            text.append(String.format("%02x", value & 0xff));
        }
        return text.toString();
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    /**
     * Renders every chunk of a run as {@code SELECT <rowExpr> FROM <driving_table>
     * WHERE <chunking_clause>} with the run's own boundaries, then executes each
     * and returns every selected value, in chunk order.
     */
    private List<String> renderedChunkRows(long runId, Table<?> table, String keyColumns,
                                           String rowExpression, String[] kinds,
                                           DSLContext context) {
        List<String> rows = new ArrayList<>();
        for (String sql : renderedChunkSql(runId, table, keyColumns, rowExpression, kinds, context)) {
            rows.addAll(context.fetch(sql).getValues(0, String.class));
        }
        return rows;
    }

    /**
     * Renders every chunk of a run through {@code dml_utils_lib.render_chunk_sql}
     * from the stored boundaries, in boundary order.
     */
    private List<String> renderedChunkSql(long runId, Table<?> table, String keyColumns,
                                          String rowExpression, String[] kinds, DSLContext context) {
        String template = "SELECT " + rowExpression + " FROM <driving_table> WHERE <chunking_clause>";
        String[] columns = keyColumns.split(",\\s*");
        return context.fetch(
                "SELECT dml_utils_lib.render_chunk_sql("
                        + " i_sql_text => ?, i_schema_name => ?, i_table_name => ?, i_table_alias => 't',"
                        + " i_key_columns => ?::name[], i_key_kinds => ?::text[],"
                        + " i_start_values => pair.o_start_values, i_end_values => pair.o_end_values,"
                        + " i_is_final => e.boundary_no = last.n) AS sql"
                        + " FROM dml_utils_data.migration_boundary AS s"
                        + " JOIN dml_utils_data.migration_boundary AS e"
                        + "   ON e.run_id = s.run_id AND e.boundary_no = s.boundary_no + 1"
                        + " CROSS JOIN (SELECT max(boundary_no) AS n FROM dml_utils_data.migration_boundary"
                        + "             WHERE run_id = ?) AS last"
                        + " CROSS JOIN LATERAL dml_utils_lib.migration_key_pair_values("
                        + "   i_start_key => s.boundary_id, i_end_key => e.boundary_id,"
                        + "   i_key_kinds => ?::text[]) AS pair"
                        + " WHERE s.run_id = ? ORDER BY s.boundary_no",
                template, schema(table), name(table), columns, kinds, runId, kinds, runId)
                .getValues("sql", String.class);
    }
}
