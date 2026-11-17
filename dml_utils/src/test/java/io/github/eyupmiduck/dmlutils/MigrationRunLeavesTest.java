package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunLeavesRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunSummaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the public partition-run introspection: {@code migration_run_leaves}
 * exposes the manifest in deterministic leaf order, and
 * {@code migration_run_summary} reports the partition flag, chunk totals and
 * leaf progress.
 */
class MigrationRunLeavesTest extends PartitionTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String TABLE = "test_partitioned_range";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Empties the range fixture and resets the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsBigintAndRange();
    }

    /**
     * The manifest is returned one row per leaf, ordered by ordinal, with the
     * identity snapshot, calculated state and per-leaf boundary count.
     */
    @Test
    void exposesTheManifestInLeafOrder() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("leaves-manifest");
        long runId = populate(label, 2);
        calculateRun(runId);

        List<MigrationRunLeavesRecord> leaves = Routines.migrationRunLeaves(
                dsl.configuration(), runId);

        assertEquals(3, leaves.size(), "one row per storage leaf");
        assertEquals(List.of(1, 2, 3),
                leaves.stream().map(MigrationRunLeavesRecord::getLeafOrdinal).toList(),
                "leaves are ordered by their manifest ordinal");
        assertEquals(List.of(
                        "test_partitioned_range_low",
                        "test_partitioned_range_mid",
                        "test_partitioned_range_high"),
                leaves.stream().map(MigrationRunLeavesRecord::getLeafName).toList(),
                "the deterministic leaf order follows the partition bound");
        assertEquals(List.of(2L, 1L, 2L),
                leaves.stream().map(MigrationRunLeavesRecord::getBoundaryCount).toList(),
                "each leaf reports its own chunk-start count");

        for (MigrationRunLeavesRecord leaf : leaves) {
            assertEquals(runId, leaf.getRunId().longValue());
            assertEquals(SCHEMA, leaf.getLeafSchemaName());
            assertEquals("calculated", leaf.getState());
            assertNotNull(leaf.getCalculatedAt(),
                    "a calculated leaf records when it was calculated");
            assertNotNull(leaf.getCreatedAt(), "the manifest row records its creation");
            assertNotNull(leaf.getUpdatedAt(), "the manifest row records its last update");
            assertNotNull(leaf.getLeafBound(), "the partition bound is snapshotted");
            assertFalse(leaf.getLeafBound().isBlank(), "the partition bound is not blank");
            assertNotNull(leaf.getLeafOid(), "the leaf OID is snapshotted");
            assertNotNull(leaf.getTerminalBoundaryId(),
                    "a calculated non-empty leaf has a high-water snapshot");
            assertNull(leaf.getRelationFilepath(),
                    "a primary-key run records no per-leaf heap filepath");
        }

        MigrationRunLeafRecord raw = dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .fetchOne();
        assertEquals(raw.getLeafOid(), leaves.getFirst().getLeafOid(),
                "the manifest exposes the stored leaf OID");
    }

    /**
     * An empty leaf is still a manifest row: it is calculated with zero
     * boundaries and no high-water snapshot.
     */
    @Test
    void includesEmptyLeavesWithZeroBoundaries() {
        insertRangeRows(0L, 1L);
        String label = uniqueLabel("leaves-empty");
        long runId = populate(label, 2);
        calculateRun(runId);

        List<MigrationRunLeavesRecord> leaves = Routines.migrationRunLeaves(
                dsl.configuration(), runId);

        assertEquals(3, leaves.size(), "empty leaves are not omitted from the manifest");
        assertEquals(List.of(1L, 0L, 0L),
                leaves.stream().map(MigrationRunLeavesRecord::getBoundaryCount).toList());
        for (MigrationRunLeavesRecord leaf : leaves) {
            assertEquals("calculated", leaf.getState(),
                    "an empty leaf is calculated rather than pending");
            assertNotNull(leaf.getCalculatedAt());
        }
        assertNotNull(leaves.get(0).getTerminalBoundaryId(),
                "the non-empty leaf snapshots its high-water key");
        assertNull(leaves.get(1).getTerminalBoundaryId(),
                "an empty leaf has no high-water key");
        assertNull(leaves.get(2).getTerminalBoundaryId(),
                "an empty leaf has no high-water key");
    }

    /**
     * A non-partition-wise run has no manifest: the leaf listing is empty and the
     * summary reports zero leaf counts while still exposing its chunk totals.
     */
    @Test
    void returnsNoLeavesForANonPartitionWiseRun() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("leaves-single");
        long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(), SCHEMA, TEST_BIGINT.getName(),
                        label, TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null,
                        false, 3, 1000);

        assertTrue(Routines.migrationRunLeaves(dsl.configuration(), runId).isEmpty(),
                "a non-partition-wise run returns no manifest rows");

        MigrationRunSummaryRecord summary = summary(label);
        assertFalse(summary.getPartitionWise(), "the summary exposes the partition flag");
        assertEquals(0L, summary.getLeafCount());
        assertEquals(0L, summary.getLeavesCalculated());
        assertEquals(0L, summary.getLeavesFailed());
        assertEquals(0L, summary.getLeavesPending());
        assertEquals(2L, summary.getTotalChunks(),
                "the non-partitioned run still reports its stored chunk total");
        assertEquals(0L, summary.getTotalChunksCompleted());
    }

    /**
     * The summary tracks the leaf states and chunk counters as a partition-wise
     * run is calculated and processed.
     */
    @Test
    void summaryReportsPartitionWiseLeafProgress() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("leaves-progress");
        long runId = populate(label, 2);

        MigrationRunSummaryRecord pending = summary(label);
        assertTrue(pending.getPartitionWise());
        assertEquals(3L, pending.getLeafCount());
        assertEquals(3L, pending.getLeavesPending(), "every leaf starts pending");
        assertEquals(0L, pending.getLeavesCalculated());
        assertEquals(0L, pending.getLeavesFailed());
        assertEquals(0L, pending.getTotalChunks(), "no boundaries are committed yet");
        assertEquals(0L, pending.getTotalChunksCompleted());

        calculateRun(runId);
        MigrationRunSummaryRecord calculated = summary(label);
        assertEquals(3L, calculated.getLeavesCalculated());
        assertEquals(0L, calculated.getLeavesPending());
        assertEquals(0L, calculated.getLeavesFailed());
        assertEquals(5L, calculated.getTotalChunks());
        assertEquals(0L, calculated.getTotalChunksCompleted());

        completeBoundaries(runId, 0L, 1L);
        assertEquals(2L, summary(label).getTotalChunksCompleted(),
                "the summary exposes the stored completion counter");
    }

    /**
     * A failed leaf is counted separately and exposed with its SQLSTATE and
     * message, while the remaining leaves stay pending.
     */
    @Test
    void summaryCountsFailedLeaves() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("leaves-failed");
        long runId = populate(label, 2);
        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "failed")
                .set(MIGRATION_RUN_LEAF.ERROR_SQLSTATE, "40001")
                .set(MIGRATION_RUN_LEAF.ERROR_MESSAGE, "serialization failure")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        MigrationRunSummaryRecord summary = summary(label);
        assertEquals(3L, summary.getLeafCount());
        assertEquals(0L, summary.getLeavesCalculated());
        assertEquals(1L, summary.getLeavesFailed());
        assertEquals(2L, summary.getLeavesPending());

        MigrationRunLeavesRecord failed = Routines.migrationRunLeaves(
                        dsl.configuration(), runId)
                .getFirst();
        assertEquals("failed", failed.getState());
        assertEquals("40001", failed.getErrorSqlstate());
        assertEquals("serialization failure", failed.getErrorMessage());
        assertNull(failed.getCalculatedAt(), "a failed leaf is not calculated");
    }

    /**
     * A leaf left {@code calculating} by a crashed worker is counted as pending,
     * so the leaf counts always add up to the manifest size.
     */
    @Test
    void summaryTreatsACalculatingLeafAsPending() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("leaves-calculating");
        long runId = populate(label, 2);
        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculating")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        MigrationRunSummaryRecord summary = summary(label);
        assertEquals(3L, summary.getLeafCount());
        assertEquals(3L, summary.getLeavesPending(),
                "a stale calculating claim is still work to do");
        assertEquals(0L, summary.getLeavesCalculated());
        assertEquals(0L, summary.getLeavesFailed());
    }

    /**
     * Creates a partition-wise run over the range fixture and returns its id.
     *
     * @param label     the run label
     * @param chunkSize the chunk size
     * @return the run id
     */
    private long populate(String label, int chunkSize) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .populateMigrationBoundaries(dsl.configuration(), SCHEMA, TABLE, label,
                        TEMPLATE, chunkSize, 1, ChunkingStrategy.primary_key, "t", 0, null,
                        true, 3, 1000);
    }


    /**
     * Completes the given chunk boundaries with a zero row count.
     *
     * @param runId       the run id
     * @param boundaryNos the chunk boundaries to complete
     */
    private void completeBoundaries(long runId, long... boundaryNos) {
        for (long boundaryNo : boundaryNos) {
            io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                    .completeMigrationBoundary(dsl.configuration(), runId, boundaryNo,
                            OffsetDateTime.now(), 0L);
        }
    }

    /**
     * Reads the single summary row for a label.
     *
     * @param label the run label
     * @return the summary row
     */
    private MigrationRunSummaryRecord summary(String label) {
        List<MigrationRunSummaryRecord> summaries = Routines.migrationRunSummary(
                dsl.configuration(), label);
        assertEquals(1, summaries.size(), "the label has exactly one run");
        return summaries.getFirst();
    }
}
