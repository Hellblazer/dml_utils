package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ClaimNextMigrationLeafRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.claim_next_migration_leaf}: leaves are claimed in
 * manifest order with the run's strategy and size, a claimed leaf becomes
 * calculating, and finalized/cancelled/non-partition-wise runs and unreadable
 * leaves are handled as documented.
 */
class ClaimNextMigrationLeafTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String TABLE = "test_partitioned_range";

    /**
     * Empties the fixture and migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * Claiming returns the first pending leaf with the run's chunk strategy and
     * size, and marks it calculating; once it is calculated the next leaf is
     * claimed.
     */
    @Test
    void claimsLeavesInManifestOrder() {
        long runId = createRun(ChunkingStrategy.blocks, 4);

        ClaimNextMigrationLeafRecord first = claim(runId).getFirst();
        assertEquals(1, first.getOLeafOrdinal());
        assertEquals(PARTITION_SCHEMA, first.getOLeafSchemaName());
        assertEquals("test_partitioned_range_low", first.getOLeafName());
        assertEquals(ChunkingStrategy.blocks, first.getOChunkBy());
        assertEquals(4, first.getOChunkSize());
        assertEquals("calculating", leafState(runId, 1));

        markCalculated(runId, 1);
        assertEquals(2, claim(runId).getFirst().getOLeafOrdinal());
    }

    /**
     * A finalized or cancelled run has no claimable leaf.
     */
    @Test
    void returnsNoLeafForFinalizedOrCancelledRuns() {
        long runId = createRun(ChunkingStrategy.primary_key, 2);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertTrue(claim(runId).isEmpty());

        long finalized = createRun(ChunkingStrategy.primary_key, 2);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(finalized))
                .execute();
        assertTrue(claim(finalized).isEmpty());
    }

    /**
     * A NULL run id, a missing run and a non-partition-wise run are rejected.
     */
    @Test
    void rejectsMissingAndNonPartitionWiseRuns() {
        assertSqlState("22023", () -> claim(null));
        assertSqlState("P0002", () -> claim(-1L));
        long plain = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, TABLE, uniqueLabel("claim-plain"), TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false);
        assertSqlState("22023", () -> claim(plain));
    }

    /**
     * A leaf the caller cannot read is rejected before it is claimed.
     */
    @Test
    void rejectsAnUnreadableLeaf() throws Exception {
        long runId = createRun(ChunkingStrategy.primary_key, 2);
        executeAsOwner("REVOKE SELECT ON " + PARTITION_SCHEMA
                + ".test_partitioned_range_low FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> {
                claim(runId);
            });
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + PARTITION_SCHEMA + ".test_partitioned_range_low TO "
                    + TEST_USER + ", dml_utils_caller");
        }
    }

    private long createRun(ChunkingStrategy chunkBy, int chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, TABLE, uniqueLabel("claim-leaf-" + chunkBy),
                TEMPLATE, chunkSize, 1, chunkBy, "t", 0, null, true);
    }

    private List<ClaimNextMigrationLeafRecord> claim(Long runId) {
        return Routines.claimNextMigrationLeaf(dsl.configuration(), runId);
    }

    private String leafState(long runId, int ordinal) {
        return dsl.select(MIGRATION_RUN_LEAF.STATE)
                .from(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .fetchOne(MIGRATION_RUN_LEAF.STATE);
    }

    private void markCalculated(long runId, int ordinal) {
        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculated")
                .set(MIGRATION_RUN_LEAF.BOUNDARY_COUNT, 0L)
                .set(MIGRATION_RUN_LEAF.CALCULATED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .execute();
    }
}
