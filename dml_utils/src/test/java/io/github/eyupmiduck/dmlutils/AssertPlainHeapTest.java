package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.assert_plain_heap} and that the block strategy is
 * rejected for a relation with no single physical heap: a plain table passes, a
 * partitioned table is rejected with {@code 42809}, a missing relation with
 * {@code 42P01}, and a block run against a partitioned table creates no run.
 */
class AssertPlainHeapTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TEMPLATE =
            "INSERT INTO dml_utils_fixtures.test_other (id, payload) "
                    + "SELECT t.id, t.payload FROM <driving_table> WHERE <chunking_clause>";

    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
    }

    /**
     * A plain table passes the check.
     */
    @Test
    void acceptsAPlainTable() {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertPlainHeap(dsl.configuration(), SCHEMA, "test_bigint");
    }

    /**
     * A partitioned table is rejected with {@code 42809}.
     */
    @Test
    void rejectsAPartitionedTable() {
        assertSqlState("42809", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertPlainHeap(dsl.configuration(),
                SCHEMA, "test_partitioned"));
    }

    /**
     * A missing relation is rejected with {@code 42P01}.
     */
    @Test
    void rejectsAMissingTable() {
        assertSqlState("42P01", () -> io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.assertPlainHeap(dsl.configuration(),
                SCHEMA, "no_such_table"));
    }

    /**
     * A block run against a partitioned table is rejected with {@code 42809}
     * before any run is created.
     */
    @Test
    void blockRunRejectsAPartitionedTable() {
        int runsBefore = dsl.fetchCount(MIGRATION_RUN);

        assertSqlState("42809", () -> Routines.runMigrationChunks(dsl.configuration(),
                TEMPLATE, SCHEMA, "test_partitioned", uniqueLabel("block-partitioned"),
                1, 1, "t", ChunkingStrategy.blocks, 1, 0, null));

        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "a rejected block run must not create a run");
    }
}
