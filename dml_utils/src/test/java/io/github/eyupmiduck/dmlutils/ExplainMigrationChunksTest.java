package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.ExplainMigrationChunkRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.ExplainMigrationChunksRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeThree.TEST_COMPOSITE_THREE;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNumeric.TEST_NUMERIC;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestTimestamptzPk.TEST_TIMESTAMPTZ_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.explain_migration_chunks}: it returns the EXPLAIN
 * plans of the boundary-population insert and of a representative non-final and
 * final chunk for the supplied template, and it is read-only (no run, no
 * boundary and no driving-table change).
 */
class ExplainMigrationChunksTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    private static final List<String> THREE_KINDS =
            List.of("boundary_population", "chunk_non_final", "chunk_final");

    private static List<String> planKinds(List<ExplainMigrationChunksRecord> plans) {
        return plans.stream().map(ExplainMigrationChunksRecord::getOPlanKind).toList();
    }

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

    /**
     * Clears the seeded fixture row and the migration metadata so each test
     * starts clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A timestamptz primary key yields the three plan kinds with timestamp
     * literals in the synthetic chunk.
     */
    @Test
    void plansATimestamptzPrimaryKey() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_TIMESTAMPTZ_PK),
                name(TEST_TIMESTAMPTZ_PK), 10, "t", ChunkingStrategy.primary_key, null);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(1).getOSqlText().contains("'2026-01-01T00:00:00Z'::timestamptz"),
                "the non-final chunk uses the synthetic timestamptz range");
        plans.forEach(plan -> assertEquals("ModifyTable", planNodeType(plan.getOPlan())));
    }

    /**
     * A bigint-keyed table yields exactly the three plan kinds in order.
     */
    @Test
    void returnsTheThreePlansInOrder() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key, null);

        assertEquals(THREE_KINDS, planKinds(plans));
    }

    /**
     * An ordered_key run resolves the index and plans a timestamptz chunk.
     */
    @Test
    void returnsTheThreePlansForAnOrderedKey() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_ORDERED_KEY), name(TEST_ORDERED_KEY),
                10, "t", ChunkingStrategy.ordered_key,
                "test_ordered_key_created_at_id_idx");

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(0).getOSqlText().contains("ORDER BY"),
                "the boundary plan scans in key order");
        assertTrue(plans.get(1).getOSqlText().contains("'2026-01-01T00:00:00Z'::timestamptz"),
                "the non-final chunk uses the synthetic timestamptz range");
    }

    /**
     * The ordered_key strategy requires an index, and the other strategies reject
     * one; both are invalid-parameter errors.
     */
    @Test
    void rejectsAnInvalidOrderedKeyOrIndexCombination() {
        assertSqlState("22023", () -> Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_ORDERED_KEY), name(TEST_ORDERED_KEY),
                10, "t", ChunkingStrategy.ordered_key, null));
        assertSqlState("22023", () -> Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT),
                10, "t", ChunkingStrategy.primary_key, "test_bigint_pkey"));
    }

    /**
     * The explicit form explains an ordered-key chunk whose values are
     * timestamptz and bigint.
     */
    @Test
    void explainsAnExplicitOrderedKeyChunk() {
        List<ExplainMigrationChunkRecord> plans = Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_ORDERED_KEY), name(TEST_ORDERED_KEY),
                new String[]{"2026-01-01T00:00:01+00", "1"},
                new String[]{"2026-01-01T00:00:03+00", "5"},
                false, "t", ChunkingStrategy.ordered_key,
                "test_ordered_key_created_at_id_idx");

        assertEquals(1, plans.size(), "one row for the explicit chunk");
        assertEquals("chunk_non_final", plans.get(0).getOPlanKind());
        assertTrue(plans.get(0).getOSqlText().contains("::timestamptz"),
                "the predicate casts the timestamp values");
        assertNotNull(plans.get(0).getOPlan(), "the row carries the JSON plan");
    }

    /**
     * The explicit form rejects block chunks; use explain_migration_chunks for
     * those synthetic plans.
     */
    @Test
    void rejectsBlockChunksOnTheExplicitForm() {
        assertSqlState("0A000", () -> Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT),
                new String[]{"0"}, new String[]{"10"}, false, "t",
                ChunkingStrategy.blocks, null));
    }

    /**
     * The block strategy plans the block boundary insert and two half-open ctid
     * ranges, without executing anything.
     */
    @Test
    void plansTheBlockStrategy() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t",
                ChunkingStrategy.blocks, null);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(0).getOSqlText().contains("INSERT INTO dml_utils_data.migration_boundary"),
                "the boundary plan is the boundary insert");
        assertTrue(plans.get(1).getOSqlText().contains("ctid")
                        && plans.get(1).getOSqlText().contains("'(0,0)'::tid")
                        && plans.get(1).getOSqlText().contains("'(10,0)'::tid"),
                "the non-final chunk is a half-open ctid range");
        assertTrue(plans.get(2).getOSqlText().contains("'(20,0)'::tid"),
                "the final chunk is a later half-open ctid range");
    }

    /**
     * A text key renders text literals in the chunk predicate and still plans
     * all three statements as a ModifyTable.
     */
    @Test
    void plansATextKeyWithTextLiterals() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_TEXT), name(TEST_TEXT), 10, "t", ChunkingStrategy.primary_key, null);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(1).getOSqlText().contains("'a'::text"),
                "the non-final text range starts at the synthetic a");
        assertTrue(plans.get(1).getOSqlText().contains("'b'::text"),
                "the non-final text range ends at the synthetic b");
        plans.forEach(plan -> assertEquals("ModifyTable", planNodeType(plan.getOPlan())));
    }

    /**
     * A uuid key renders uuid literals in the chunk predicate and still plans
     * all three statements as a ModifyTable.
     */
    @Test
    void plansAUuidKeyWithUuidLiterals() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_UUID), name(TEST_UUID), 10, "t", ChunkingStrategy.primary_key, null);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(1).getOSqlText()
                        .contains("'00000000-0000-0000-0000-000000000000'::uuid"),
                "the non-final uuid range starts at the all-zero value");
        assertTrue(plans.get(1).getOSqlText()
                        .contains("'00000000-0000-0000-0000-000000000001'::uuid"),
                "the non-final uuid range ends at the all-zero-plus-one value");
        plans.forEach(plan -> assertEquals("ModifyTable", planNodeType(plan.getOPlan())));
    }

    /**
     * A composite key renders a row-value predicate with each position cast to
     * its own kind (bigint and text here).
     */
    @Test
    void plansACompositeKeyWithEachKindsLiteral() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), 10, "t", ChunkingStrategy.primary_key, null);

        assertEquals(THREE_KINDS, planKinds(plans));
        String nonFinal = plans.get(1).getOSqlText();
        assertTrue(nonFinal.contains("t.a = '0'::bigint"),
                "the predicate constrains the composite key column by column");
        assertTrue(nonFinal.contains("'0'::bigint"), "the bigint position uses a bigint literal");
        assertTrue(nonFinal.contains("'a'::text"), "the text position uses a text literal");
        assertTrue(plans.get(2).getOSqlText().contains("t.b <= "),
                "the final composite chunk is inclusive");
    }

    /**
     * Each row carries the statement that was explained and its plan: the
     * boundary insert and the chunk updates all plan as a ModifyTable, and the
     * non-final chunk uses a half-open range while the final one is inclusive.
     */
    @Test
    void plansTheBoundaryInsertAndBothChunkForms() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key, null);

        ExplainMigrationChunksRecord boundary = plans.get(0);
        assertTrue(boundary.getOSqlText().contains("INSERT INTO dml_utils_data.migration_boundary"),
                "the boundary plan explains the boundary insert");
        assertEquals("ModifyTable", planNodeType(boundary.getOPlan()));

        ExplainMigrationChunksRecord nonFinal = plans.get(1);
        assertTrue(nonFinal.getOSqlText().contains("t.id < "),
                "the non-final chunk is half-open");
        assertEquals("ModifyTable", planNodeType(nonFinal.getOPlan()));

        ExplainMigrationChunksRecord finalChunk = plans.get(2);
        assertTrue(finalChunk.getOSqlText().contains("t.id <= "),
                "the final chunk is inclusive");
        assertEquals("ModifyTable", planNodeType(finalChunk.getOPlan()));
    }

    /**
     * The call is read-only: it creates no run or boundary and does not run the
     * template (the seeded payload is unchanged).
     */
    @Test
    void writesNothing() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID, TEST_BIGINT.PAYLOAD)
                .values(1L, "original")
                .execute();

        Routines.explainMigrationChunks(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key, null);

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN), "no run is created");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY), "no boundary is written");
        assertEquals("original",
                dsl.select(TEST_BIGINT.PAYLOAD)
                        .from(TEST_BIGINT)
                        .where(TEST_BIGINT.ID.eq(1L))
                        .fetchOne(TEST_BIGINT.PAYLOAD),
                "the template must not run");
    }

    /**
     * A template without the required tokens is rejected with {@code 22023}
     * before any plan is produced.
     */
    @Test
    void rejectsABadTemplate() {
        assertSqlState("22023", () -> Routines.explainMigrationChunks(dsl.configuration(),
                "UPDATE nothing", schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key, null));
    }

    /**
     * A table whose primary key is an unsupported type is rejected with
     * {@code 22023}.
     */
    @Test
    void rejectsAnUnsupportedKeyType() {
        assertSqlState("22023", () -> Routines.explainMigrationChunks(dsl.configuration(),
                TEMPLATE, schema(TEST_NUMERIC), name(TEST_NUMERIC), 10, "t", ChunkingStrategy.primary_key, null));
    }

    /**
     * A missing driving table is rejected with {@code 42P01}.
     */
    @Test
    void rejectsAMissingTable() {
        assertSqlState("42P01", () -> Routines.explainMigrationChunks(dsl.configuration(),
                TEMPLATE, "dml_utils_fixtures", "no_such_table", 10, "t", ChunkingStrategy.primary_key, null));
    }

    /**
     * The block strategy rejects a partitioned table with {@code 42809}.
     */
    @Test
    void rejectsAPartitionedTableForBlocks() {
        assertSqlState("42809", () -> Routines.explainMigrationChunks(dsl.configuration(),
                TEMPLATE, "dml_utils_fixtures", "test_partitioned", 10, "t", ChunkingStrategy.blocks, null));
    }

    /**
     * The explicit-boundary form explains one chunk whose first key column
     * differs, so the predicate is the full three-box decomposition.
     */
    @Test
    void explainsAChunkWhoseFirstKeyColumnDiffers() {
        List<ExplainMigrationChunkRecord> plans = Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                new String[]{"7", "a"}, new String[]{"9", "b"}, false, "t", ChunkingStrategy.primary_key, null);

        assertEquals(1, plans.size(), "one row for the explained chunk");
        assertEquals("chunk_non_final", plans.get(0).getOPlanKind());
        String sql = plans.get(0).getOSqlText();
        assertTrue(sql.contains("t.a = '7'::bigint AND t.b >= 'a'::text"),
                "the lower tail fixes the shared prefix and opens the suffix");
        assertTrue(sql.contains("t.a > '7'::bigint AND t.a < '9'::bigint"),
                "the middle box ranges over the first differing column");
        assertTrue(sql.contains("t.a = '9'::bigint AND t.b < 'b'::text"),
                "the upper tail fixes the end column and closes the suffix");
        assertEquals("ModifyTable", planNodeType(plans.get(0).getOPlan()));
    }

    /**
     * A chunk whose leading key column is equal in both boundaries (only the
     * second column differs) renders the single-box prefix-equality shape that
     * the synthetic explain never produces.
     */
    @Test
    void explainsAChunkWhoseLeadingKeyColumnIsEqual() {
        List<ExplainMigrationChunkRecord> plans = Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                new String[]{"7", "a"}, new String[]{"7", "b"}, false, "t", ChunkingStrategy.primary_key, null);

        assertEquals(1, plans.size());
        assertEquals("chunk_non_final", plans.get(0).getOPlanKind());
        String sql = plans.get(0).getOSqlText();
        assertTrue(sql.contains("t.a = '7'::bigint AND t.b >= 'a'::text AND t.b < 'b'::text"),
                "the whole range is one equality-plus-scalar-range box");
        assertFalse(sql.contains(" OR "), "a single differing last column is one box");
        assertEquals("ModifyTable", planNodeType(plans.get(0).getOPlan()));
    }

    /**
     * The explicit final chunk uses an inclusive upper bound on its last key
     * column.
     */
    @Test
    void explainsAnExplicitFinalChunkInclusively() {
        List<ExplainMigrationChunkRecord> plans = Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                new String[]{"7", "a"}, new String[]{"7", "b"}, true, "t", ChunkingStrategy.primary_key, null);

        assertEquals("chunk_final", plans.get(0).getOPlanKind());
        assertTrue(plans.get(0).getOSqlText().contains("t.b <= 'b'::text"),
                "the final chunk captures the upper boundary");
    }

    /**
     * A three-column key whose second column differs renders the three-box
     * decomposition: a lower tail, the middle and an upper tail.
     */
    @Test
    void explainsAThreeColumnChunkWhoseMiddleKeyColumnDiffers() {
        List<ExplainMigrationChunkRecord> plans = Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_THREE), name(TEST_COMPOSITE_THREE),
                new String[]{"1", "a", "00000000-0000-0000-0000-000000000000"},
                new String[]{"1", "b", "00000000-0000-0000-0000-000000000001"}, false, "t", ChunkingStrategy.primary_key, null);

        assertEquals(1, plans.size());
        String sql = plans.get(0).getOSqlText();
        assertTrue(sql.contains("t.b = '1'::bigint AND t.a = 'a'::text"
                        + " AND t.c >= '00000000-0000-0000-0000-000000000000'::uuid"),
                "the lower tail fixes the shared prefix");
        assertTrue(sql.contains("t.b = '1'::bigint AND t.a > 'a'::text AND t.a < 'b'::text"),
                "the middle box ranges over the differing column");
        assertTrue(sql.contains("t.b = '1'::bigint AND t.a = 'b'::text"
                        + " AND t.c < '00000000-0000-0000-0000-000000000001'::uuid"),
                "the upper tail fixes the end column");
    }

    /**
     * A three-column key where only the last column differs renders one scalar
     * range box.
     */
    @Test
    void explainsAThreeColumnChunkWhoseLastKeyColumnDiffers() {
        List<ExplainMigrationChunkRecord> plans = Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_THREE), name(TEST_COMPOSITE_THREE),
                new String[]{"1", "a", "00000000-0000-0000-0000-000000000000"},
                new String[]{"1", "a", "00000000-0000-0000-0000-000000000001"}, false, "t", ChunkingStrategy.primary_key, null);

        String sql = plans.get(0).getOSqlText();
        assertTrue(sql.contains("t.b = '1'::bigint AND t.a = 'a'::text"));
        assertTrue(sql.contains("t.c >= '00000000-0000-0000-0000-000000000000'::uuid"));
        assertTrue(sql.contains("t.c < '00000000-0000-0000-0000-000000000001'::uuid"));
        assertFalse(sql.contains(" OR "), "only the last column differs, so one box");
    }

    /**
     * A reversed explicit range is rejected with {@code 22023} rather than
     * explaining a predicate that matches no rows.
     */
    @Test
    void rejectsAReversedExplicitRange() {
        assertSqlState("22023", () -> Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                new String[]{"9", "b"}, new String[]{"7", "a"}, false, "t", ChunkingStrategy.primary_key, null));
    }

    /**
     * An explicit non-final range whose bounds are equal is empty and rejected
     * with {@code 22023}.
     */
    @Test
    void rejectsAnEmptyExplicitRange() {
        assertSqlState("22023", () -> Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                new String[]{"7", "a"}, new String[]{"7", "a"}, false, "t", ChunkingStrategy.primary_key, null));
    }

    /**
     * A boundary with the wrong number of values is rejected with {@code 22023}.
     */
    @Test
    void rejectsAWrongNumberOfBoundaryValues() {
        assertSqlState("22023", () -> Routines.explainMigrationChunk(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                new String[]{"7"}, new String[]{"7", "b"}, false, "t", ChunkingStrategy.primary_key, null));
    }

    /**
     * The explicit-boundary call is read-only: it writes no run or boundary and
     * does not run the template.
     */
    @Test
    void explicitBoundariesWriteNothing() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID, TEST_BIGINT.PAYLOAD)
                .values(1L, "original")
                .execute();

        Routines.explainMigrationChunk(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT),
                new String[]{"0"}, new String[]{"10"}, false, "t", ChunkingStrategy.primary_key, null);

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN), "no run is created");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY), "no boundary is written");
        assertEquals("original",
                dsl.select(TEST_BIGINT.PAYLOAD)
                        .from(TEST_BIGINT)
                        .where(TEST_BIGINT.ID.eq(1L))
                        .fetchOne(TEST_BIGINT.PAYLOAD),
                "the template must not run");
    }
}
