package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.OrderedKeyLeafIndexesRecord;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.ordered_key_leaf_indexes}: a partitioned index's
 * inheritance tree yields exactly one matching child index for every storage
 * leaf, including a multi-level tree, while a plain index or an unknown OID
 * yields no rows.
 */
class OrderedKeyLeafIndexesTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";

    private Set<String> storageLeaves(String parent) {
        return new HashSet<>(dsl.fetch(
                        "SELECT leaf.relid::regclass::text FROM pg_partition_tree(?::regclass) AS leaf"
                                + " WHERE leaf.isleaf",
                        SCHEMA + "." + parent)
                .into(String.class));
    }

    /**
     * Maps every index OID to the relation it is on in one catalog read, so the
     * per-row assertions do not issue a query per row.
     *
     * @return index OID to qualified table name
     */
    private Map<Long, String> indexedTables() {
        return dsl.fetch("""
                        SELECT i.indexrelid AS oid, i.indrelid::regclass::text AS table_name
                        FROM pg_catalog.pg_index AS i
                        """)
                .intoMap(row -> row.get("oid", Long.class),
                        row -> row.get("table_name", String.class));
    }

    private List<OrderedKeyLeafIndexesRecord> childIndexes(String parentIndex) {
        return Routines.orderedKeyLeafIndexes(dsl.configuration(),
                relationOid(SCHEMA + "." + parentIndex));
    }

    /**
     * A range-partitioned parent index has one child index per leaf, and each
     * child index is on a distinct storage leaf.
     */
    @Test
    void returnsOneChildIndexPerRangeLeaf() {
        List<OrderedKeyLeafIndexesRecord> rows =
                childIndexes("test_partitioned_range_ordered_key_idx");

        assertEquals(3, rows.size(), "one child index per range leaf");
        Map<Long, String> indexedTables = indexedTables();
        Set<String> indexedLeaves = new HashSet<>();
        Set<String> childNames = new HashSet<>();
        for (OrderedKeyLeafIndexesRecord row : rows) {
            assertNotNull(row.getLeafOid());
            assertNotNull(row.getChildIndexOid());
            assertNotNull(row.getChildIndexName());
            assertTrue(childNames.add(row.getChildIndexName()),
                    "each child index name is distinct");
            indexedLeaves.add(indexedTables.get(row.getChildIndexOid()));
        }
        assertEquals(storageLeaves("test_partitioned_range"), indexedLeaves,
                "the child indexes cover exactly the storage leaves");
    }

    /**
     * A multi-level index tree is walked recursively, so the two hash leaves
     * below the range sub-parent each contribute their own child index.
     */
    @Test
    void walksAMultiLevelIndexTree() {
        List<OrderedKeyLeafIndexesRecord> rows =
                childIndexes("test_partitioned_multi_ordered_key_idx");

        assertEquals(3, rows.size(), "two hash leaves plus the high leaf");
        Map<Long, String> indexedTables = indexedTables();
        Set<String> indexedLeaves = new HashSet<>();
        for (OrderedKeyLeafIndexesRecord row : rows) {
            indexedLeaves.add(indexedTables.get(row.getChildIndexOid()));
        }
        assertEquals(storageLeaves("test_partitioned_multi"), indexedLeaves,
                "the recursive walk covers every storage leaf");
    }

    /**
     * A plain table's index has no inheritance tree, so it yields no rows.
     */
    @Test
    void returnsNothingForAPlainIndex() {
        assertEquals(0, childIndexes("test_bigint_pkey").size());
    }

    /**
     * An OID that is not a partitioned index yields no rows rather than failing.
     */
    @Test
    void returnsNothingForAnUnknownOid() {
        assertEquals(0, Routines.orderedKeyLeafIndexes(dsl.configuration(), 0L).size());
    }
}
