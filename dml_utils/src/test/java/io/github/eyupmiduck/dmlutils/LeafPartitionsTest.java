package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.LeafPartitionsRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@code dml_utils_lib.leaf_partitions}: the deterministic leaf order,
 * sub-partition expansion, empty and detached leaves, the per-strategy filepath
 * and the rejections for a non-partitioned parent, a non-table leaf and a leaf
 * the caller cannot read.
 */
class LeafPartitionsTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";

    /**
     * Empties the range fixture so the enumeration tests are independent of each
     * other.
     */
    @BeforeEach
    void reset() {
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A range parent yields its leaves in partition-bound order, not name or
     * creation order, and the key strategies record no relation filepath.
     */
    @Test
    void enumeratesRangeLeavesByBound() {
        dsl.insertInto(TEST_PARTITIONED_RANGE,
                        TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                .values(50L, "only-in-the-low-leaf")
                .execute();

        List<LeafPartitionsRecord> leaves = leaves("test_partitioned_range",
                ChunkingStrategy.primary_key);

        assertEquals(List.of("test_partitioned_range_low", "test_partitioned_range_mid",
                        "test_partitioned_range_high"),
                leaves.stream().map(LeafPartitionsRecord::getLeafName).toList(),
                "leaves should be ordered by bound, not by name or creation order");
        assertEquals(List.of(1, 2, 3),
                leaves.stream().map(LeafPartitionsRecord::getLeafOrdinal).toList());
        assertEquals(List.of("FOR VALUES FROM ('0') TO ('100')",
                        "FOR VALUES FROM ('100') TO ('200')",
                        "FOR VALUES FROM ('200') TO ('300')"),
                leaves.stream().map(LeafPartitionsRecord::getLeafBound).toList());
        for (LeafPartitionsRecord leaf : leaves) {
            assertEquals(SCHEMA, leaf.getLeafSchemaName());
            assertNotNull(leaf.getLeafOid());
            assertNull(leaf.getRelationFilepath(),
                    "a key strategy has no per-leaf heap filepath");
        }
    }

    /**
     * Re-enumerating the same tree yields the same ordinals, names and OIDs.
     */
    @Test
    void enumeratesTheSameTreeIdentically() {
        List<LeafPartitionsRecord> first = leaves("test_partitioned_range",
                ChunkingStrategy.primary_key);
        List<LeafPartitionsRecord> second = leaves("test_partitioned_range",
                ChunkingStrategy.primary_key);

        assertEquals(first.stream().map(LeafPartitionsRecord::getLeafOrdinal).toList(),
                second.stream().map(LeafPartitionsRecord::getLeafOrdinal).toList());
        assertEquals(first.stream().map(LeafPartitionsRecord::getLeafName).toList(),
                second.stream().map(LeafPartitionsRecord::getLeafName).toList());
        assertEquals(first.stream().map(LeafPartitionsRecord::getLeafOid).toList(),
                second.stream().map(LeafPartitionsRecord::getLeafOid).toList());
    }

    /**
     * A blocks run records each leaf's physical relation filepath.
     */
    @Test
    void recordsTheLeafFilepathForBlocks() {
        List<LeafPartitionsRecord> leaves = leaves("test_partitioned_range",
                ChunkingStrategy.blocks);

        assertEquals(3, leaves.size());
        for (LeafPartitionsRecord leaf : leaves) {
            String expected = dsl.fetchOne(
                            "SELECT pg_catalog.pg_relation_filepath(?::regclass)",
                            SCHEMA + "." + leaf.getLeafName())
                    .get(0, String.class);
            assertEquals(expected, leaf.getRelationFilepath(),
                    "the leaf filepath should identify " + leaf.getLeafName());
        }
    }

    /**
     * A sub-partitioned leaf is expanded to its storage leaves, ordered by bound
     * and then name.
     */
    @Test
    void expandsSubPartitionedLeaves() {
        List<LeafPartitionsRecord> leaves = leaves("test_partitioned_multi",
                ChunkingStrategy.primary_key);

        assertEquals(List.of("test_partitioned_multi_high",
                        "test_partitioned_multi_low_h0",
                        "test_partitioned_multi_low_h1"),
                leaves.stream().map(LeafPartitionsRecord::getLeafName).toList());
        assertEquals(List.of(1, 2, 3),
                leaves.stream().map(LeafPartitionsRecord::getLeafOrdinal).toList());
    }

    /**
     * List leaves are ordered by their bound expression, so the leaf for 1 and 2
     * precedes the leaf for 3 even though it was created later.
     */
    @Test
    void enumeratesListLeavesByBound() {
        List<LeafPartitionsRecord> leaves = leaves("test_partitioned_list",
                ChunkingStrategy.primary_key);

        assertEquals(List.of("test_partitioned_list_a", "test_partitioned_list_b"),
                leaves.stream().map(LeafPartitionsRecord::getLeafName).toList());
        assertEquals(List.of("FOR VALUES IN (1, 2)", "FOR VALUES IN (3)"),
                leaves.stream().map(LeafPartitionsRecord::getLeafBound).toList());
    }

    /**
     * A partitioned parent with no leaves enumerates to an empty list rather than
     * failing.
     */
    @Test
    void returnsNoLeavesForAnEmptyPartitionedParent() {
        assertTrue(leaves("test_partitioned_empty", ChunkingStrategy.primary_key).isEmpty());
    }

    /**
     * A detached leaf is no longer part of the tree, so it is not enumerated; the
     * remaining leaf keeps its ordinal.
     */
    @Test
    void doesNotEnumerateADetachedLeaf() throws Exception {
        executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_detach"
                + " DETACH PARTITION " + SCHEMA + ".test_partitioned_detach_a");
        try {
            List<LeafPartitionsRecord> leaves = leaves("test_partitioned_detach",
                    ChunkingStrategy.primary_key);

            assertEquals(List.of("test_partitioned_detach_b"),
                    leaves.stream().map(LeafPartitionsRecord::getLeafName).toList());
            assertEquals(List.of(1),
                    leaves.stream().map(LeafPartitionsRecord::getLeafOrdinal).toList());
        } finally {
            executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_detach"
                    + " ATTACH PARTITION " + SCHEMA + ".test_partitioned_detach_a"
                    + " FOR VALUES FROM (0) TO (10)");
        }
    }

    /**
     * A plain table is not a partition parent and is rejected with
     * {@code 42809}.
     */
    @Test
    void rejectsANonPartitionedTable() {
        assertSqlState("42809", () -> leaves("test_bigint", ChunkingStrategy.primary_key));
    }

    /**
     * A missing schema and a missing relation fail with their catalog codes.
     */
    @Test
    void rejectsAMissingSchemaOrRelation() {
        assertSqlState("3F000", () -> Routines.leafPartitions(dsl.configuration(),
                "no_such_schema", "no_such_table", ChunkingStrategy.primary_key));
        assertSqlState("42P01", () -> leaves("no_such_table", ChunkingStrategy.primary_key));
    }

    /**
     * A foreign-table partition has no heap, so the enumeration rejects it with
     * {@code 42809} instead of treating it as a storage leaf. The foreign leaf is
     * built per test because the fixture codegen container does not install the
     * {@code postgres_fdw} extension.
     */
    @Test
    void rejectsAForeignLeaf() throws Exception {
        try {
            // The server is cluster-scoped, so IF NOT EXISTS keeps a leaked
            // server from an interrupted earlier run from failing this one.
            executeAsOwner("CREATE SERVER IF NOT EXISTS leaf_partitions_foreign_server"
                    + " FOREIGN DATA WRAPPER postgres_fdw");
            executeAsOwner("CREATE FOREIGN TABLE " + SCHEMA + ".test_partitioned_foreign_leaf"
                    + " (id integer NOT NULL, payload text) SERVER leaf_partitions_foreign_server");
            executeAsOwner("ALTER TABLE " + SCHEMA + ".test_partitioned_foreign"
                    + " ATTACH PARTITION " + SCHEMA + ".test_partitioned_foreign_leaf"
                    + " FOR VALUES FROM (0) TO (100)");

            assertSqlState("42809", () -> leaves("test_partitioned_foreign",
                    ChunkingStrategy.primary_key));
            assertSqlState("42809", () -> leaves("test_partitioned_foreign",
                    ChunkingStrategy.blocks));
        } finally {
            executeAsOwner("DROP FOREIGN TABLE IF EXISTS " + SCHEMA
                    + ".test_partitioned_foreign_leaf");
            executeAsOwner("DROP SERVER IF EXISTS leaf_partitions_foreign_server");
        }
    }

    /**
     * A leaf the caller cannot read fails before any run exists, with
     * {@code 42501}.
     */
    @Test
    void rejectsALeafTheCallerCannotRead() throws Exception {
        executeAsOwner("REVOKE SELECT ON " + SCHEMA + ".test_partitioned_list_a"
                + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertEquals(Boolean.FALSE, dsl.fetchOne(
                                    "SELECT pg_catalog.has_table_privilege(?::regclass, 'SELECT')",
                                    SCHEMA + ".test_partitioned_list_a")
                            .get(0, Boolean.class),
                    "the revoke should remove the test role's SELECT privilege");
            assertSqlState("42501", () -> leaves("test_partitioned_list",
                    ChunkingStrategy.primary_key));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + SCHEMA + ".test_partitioned_list_a TO " + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * A NULL strategy is rejected instead of silently degrading to the key path
     * (the filepath CASE would be NULL for every leaf).
     */
    @Test
    void rejectsANullStrategy() {
        assertSqlState("22023", () -> Routines.leafPartitions(dsl.configuration(), SCHEMA,
                "test_partitioned_range", null));
    }

    /**
     * Date bounds are deparsed and sorted in the manifest order, so the January
     * leaf comes before the February one even though the fixtures were created
     * late-first.
     */
    @Test
    void ordersDateBoundsByBound() {
        assertEquals(List.of("test_partitioned_date_january", "test_partitioned_date_february"),
                leaves("test_partitioned_date", ChunkingStrategy.primary_key)
                        .stream().map(LeafPartitionsRecord::getLeafName).toList(),
                "the date bounds decide the manifest order, not the names or OIDs");
    }

    /**
     * The manifest order sorts the deparsed bounds, whose text depends on
     * TimeZone/DateStyle/IntervalStyle/extra_float_digits. Both the enumerator
     * and the live-bound assertion pin them, so the manifest text and the
     * byte-wise comparison are session-independent.
     */
    @Test
    void pinsTheStylesThatDecideTheManifestOrder() {
        for (String function : List.of("leaf_partitions",
                "assert_live_leaf_matches_manifest")) {
            String config = dsl.fetchOne("""
                            SELECT coalesce(pg_catalog.array_to_string(p.proconfig, ','), '')
                            FROM pg_catalog.pg_proc AS p
                            WHERE p.pronamespace = 'dml_utils_lib'::pg_catalog.regnamespace
                              AND p.proname = ?
                            """, function)
                    .get(0, String.class)
                    .toLowerCase(java.util.Locale.ROOT);
            assertTrue(config.contains("timezone=utc"),
                    () -> function + " must pin TimeZone: " + config);
            assertTrue(config.contains("datestyle=iso, mdy"),
                    () -> function + " must pin DateStyle: " + config);
            assertTrue(config.contains("intervalstyle=postgres"),
                    () -> function + " must pin IntervalStyle: " + config);
            assertTrue(config.contains("extra_float_digits=0"),
                    () -> function + " must pin extra_float_digits: " + config);
        }
    }

    /**
     * A timestamptz bound is deparsed in the pinned UTC zone: the same tree
     * yields the same text in a session set to another time zone.
     */
    @Test
    void ordersTimestamptzBoundsIndependentlyOfTimeZone() {
        List<String> utc = leaves("test_partitioned_time", ChunkingStrategy.primary_key)
                .stream().map(LeafPartitionsRecord::getLeafBound).toList();
        assertEquals(1, utc.size());
        assertTrue(utc.getFirst().contains("+00"), utc.toString());

        dsl.transaction(config -> {
            org.jooq.DSLContext tx = org.jooq.impl.DSL.using(config);
            tx.execute("SET LOCAL timezone = 'America/New_York'");
            List<String> newYork = Routines.leafPartitions(tx.configuration(), SCHEMA,
                            "test_partitioned_time", ChunkingStrategy.primary_key)
                    .stream().map(LeafPartitionsRecord::getLeafBound).toList();
            assertEquals(utc, newYork, "TimeZone must not change the deparsed bound");
        });
    }

    /**
     * A text bound containing a backslash is deparsed under the pinned
     * standard_conforming_strings, so a session with the setting off sees the
     * same bound text and order.
     */
    @Test
    void textBoundsIgnoreStandardConformingStrings() {
        List<String> pinned = leaves("test_partitioned_text_escape",
                ChunkingStrategy.primary_key).stream()
                .map(LeafPartitionsRecord::getLeafBound).toList();
        assertEquals(1, pinned.size());
        assertTrue(pinned.getFirst().contains("\\"), pinned.toString());

        // PostgreSQL 19 removed non-standard string literals, so the setting can
        // no longer be turned off there; the pin is then a no-op rather than a
        // behaviour change.
        assumeTrue(supportsNonStandardStringLiterals(),
                "standard_conforming_strings = off is no longer supported");

        dsl.transaction(config -> {
            org.jooq.DSLContext tx = org.jooq.impl.DSL.using(config);
            tx.execute("SET LOCAL standard_conforming_strings = off");
            List<String> unpinned = Routines.leafPartitions(tx.configuration(), SCHEMA,
                            "test_partitioned_text_escape", ChunkingStrategy.primary_key)
                    .stream().map(LeafPartitionsRecord::getLeafBound).toList();
            assertEquals(pinned, unpinned,
                    "standard_conforming_strings must not change the bound");
        });
    }

    /**
     * A bytea bound is deparsed under the pinned bytea_output, so a session set to
     * escape output sees the same bound text as the default hex output.
     */
    @Test
    void byteaBoundsIgnoreByteaOutput() {
        List<String> pinned = leaves("test_partitioned_bytea",
                ChunkingStrategy.primary_key).stream()
                .map(LeafPartitionsRecord::getLeafBound).toList();
        assertEquals(1, pinned.size());
        assertTrue(pinned.getFirst().contains("\\x01"), pinned.toString());

        dsl.transaction(config -> {
            org.jooq.DSLContext tx = org.jooq.impl.DSL.using(config);
            tx.execute("SET LOCAL bytea_output = 'escape'");
            List<String> escaped = Routines.leafPartitions(tx.configuration(), SCHEMA,
                            "test_partitioned_bytea", ChunkingStrategy.primary_key)
                    .stream().map(LeafPartitionsRecord::getLeafBound).toList();
            assertEquals(pinned, escaped, "bytea_output must not change the bound");
        });
    }

    /**
     * Whether this server still allows non-standard string literals
     * ({@code standard_conforming_strings = off}); PostgreSQL 19 rejects them.
     *
     * @return true when the off setting can be applied
     */
    private boolean supportsNonStandardStringLiterals() {
        return Boolean.TRUE.equals(dsl.transactionResult(config -> {
            try {
                org.jooq.impl.DSL.using(config)
                        .execute("SET LOCAL standard_conforming_strings = off");
                return true;
            } catch (org.jooq.exception.DataAccessException e) {
                return false;
            }
        }));
    }

    private List<LeafPartitionsRecord> leaves(String table, ChunkingStrategy chunkBy) {
        return Routines.leafPartitions(dsl.configuration(), SCHEMA, table, chunkBy);
    }

}
