package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ClaimNextMigrationLeafRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.claim_next_migration_leaf}: leaves are claimed in
 * manifest order with the run's strategy and size, a claimed leaf becomes
 * calculating, and finalized/cancelled/non-partition-wise runs and unreadable
 * leaves are handled as documented.
 */
class ClaimNextMigrationLeafTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Sets a session's statement timeout.
     *
     * @param connection the connection
     * @param timeout    the timeout, as a psql interval literal
     * @throws Exception if the SET fails
     */
    private static void setTimeout(Connection connection, String timeout) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '" + timeout + "'");
        }
    }

    /**
     * Empties the fixture and migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndRange();
    }

    /**
     * Claiming returns the first pending leaf with the run's chunk strategy and
     * size, and marks it calculating; once it is calculated the next leaf is
     * claimed.
     */
    @Test
    void claimsLeavesInManifestOrder() {
        long runId = populatePartitionWise("claim-leaf-blocks", PARTITIONED_RANGE, TEMPLATE, ChunkingStrategy.blocks, 4, null);

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
        long runId = populatePartitionWise("claim-leaf-primary_key", PARTITIONED_RANGE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertTrue(claim(runId).isEmpty());

        long finalized = populatePartitionWise("claim-leaf-primary_key", PARTITIONED_RANGE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
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
        assertDomainViolation(() -> claim(null));
        assertSqlState("P0002", () -> claim(-1L));
        long plain = Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, PARTITIONED_RANGE, uniqueLabel("claim-plain"), TEMPLATE, 2, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false, 3, 1000);
        assertSqlState("22023", () -> claim(plain));
    }

    /**
     * A failed leaf and a stale (crashed) calculating leaf are both retryable, and
     * reclaiming clears the previous count, timestamp and error fields.
     */
    @Test
    void reclaimsFailedAndStaleLeavesAndResetsTheirState() {
        long runId = populatePartitionWise("claim-leaf-retry", PARTITIONED_RANGE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);

        markLeaf(runId, 1, "failed", "22012", "division by zero");
        assertEquals(1, claim(runId).getFirst().getOLeafOrdinal());
        assertLeafReset(runId, 1);

        markCalculated(runId, 1);
        // A crashed claim leaves state = calculating with a partial count.
        markLeaf(runId, 2, "calculating", null, null);
        assertEquals(2, claim(runId).getFirst().getOLeafOrdinal());
        assertLeafReset(runId, 2);
    }

    /**
     * The run-row lock serializes two sessions: a second claim waits while the
     * first session holds its transaction, then claims the next leaf after the
     * holder commits.
     *
     * @throws Exception if the connections fail
     */
    @Test
    void serializesTwoSessionsOnTheRunRow() throws Exception {
        long runId = populatePartitionWise("claim-leaf-two-sessions", PARTITIONED_RANGE,
                TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        try (Connection holder = openTestConnection();
             Connection waiter = openTestConnection()) {
            holder.setAutoCommit(false);
            waiter.setAutoCommit(false);
            DSLContext holderDsl = DSL.using(holder, SQLDialect.POSTGRES);
            DSLContext waiterDsl = DSL.using(waiter, SQLDialect.POSTGRES);

            assertEquals(1, claim(holderDsl, runId).getFirst().getOLeafOrdinal(),
                    "the holder claims the first leaf and holds the run row");

            // A short timeout makes the serialization visible without hanging.
            setTimeout(waiter, "250ms");
            assertSqlState("57014", () -> claim(waiterDsl, runId));
            waiter.rollback();

            holder.commit();
            setTimeout(waiter, "5s");
            assertEquals(1, claim(waiterDsl, runId).getFirst().getOLeafOrdinal(),
                    "a calculating leaf is retryable, so the second session reclaims it");
            waiter.commit();

            markCalculated(runId, 1);
            assertEquals(2, claim(runId).getFirst().getOLeafOrdinal(),
                    "once the reclaimed leaf is calculated, the next leaf is claimed");
        }
    }

    /**
     * A leaf the caller cannot read is rejected before it is claimed.
     */
    @Test
    void rejectsAnUnreadableLeaf() throws Exception {
        long runId = populatePartitionWise("claim-leaf-primary_key", PARTITIONED_RANGE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
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

    private List<ClaimNextMigrationLeafRecord> claim(Long runId) {
        return Routines.claimNextMigrationLeaf(dsl.configuration(), runId);
    }

    private List<ClaimNextMigrationLeafRecord> claim(DSLContext context, long runId) {
        return Routines.claimNextMigrationLeaf(context.configuration(), runId);
    }

    /**
     * Forces a leaf into the given state with stale count and error fields.
     *
     * @param runId    the run id
     * @param ordinal  the leaf ordinal
     * @param state    the state to store
     * @param sqlstate the error SQLSTATE (only a failed leaf carries one)
     * @param message  the error message (only a failed leaf carries one)
     */
    private void markLeaf(long runId, int ordinal, String state, String sqlstate,
                          String message) {
        // The table checks require no calculated_at, no boundary count and both
        // error fields only for a failed leaf.
        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, state)
                .set(MIGRATION_RUN_LEAF.BOUNDARY_COUNT, 0L)
                .set(MIGRATION_RUN_LEAF.ERROR_SQLSTATE, sqlstate)
                .set(MIGRATION_RUN_LEAF.ERROR_MESSAGE, message)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .execute();
    }

    /**
     * Asserts a reclaimed leaf is calculating with its stale state cleared.
     *
     * @param runId   the run id
     * @param ordinal the leaf ordinal
     */
    private void assertLeafReset(long runId, int ordinal) {
        MigrationRunLeafRecord leaf = dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .fetchOne();
        assertEquals("calculating", leaf.getState());
        assertEquals(0L, leaf.getBoundaryCount(), "the stale count is reset");
        assertNull(leaf.getCalculatedAt(), "the stale timestamp is cleared");
        assertNull(leaf.getErrorSqlstate(), "the stale SQLSTATE is cleared");
        assertNull(leaf.getErrorMessage(), "the stale message is cleared");
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
