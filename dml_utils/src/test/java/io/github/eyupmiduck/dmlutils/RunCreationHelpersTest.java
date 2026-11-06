package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunLeafRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ResolveMigrationCreationKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the run-creation helpers: caller privilege checking, key/filepath
 * resolution per strategy, the partition manifest snapshot and single-relation
 * boundary population.
 */
class RunCreationHelpersTest extends PartitionTestBase {

    private static final String TEMPLATE = "SELECT 1";

    /**
     * Clears fixtures and metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * The caller check accepts a readable relation and rejects a revoked one.
     */
    @Test
    void checksTheCallerSelectPrivilege() throws Exception {
        Routines.assertCallerCanSelectRelation(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName());

        executeAsOwner("REVOKE SELECT ON " + TEST_BIGINT.getSchema().getName()
                + "." + TEST_BIGINT.getName() + " FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.assertCallerCanSelectRelation(
                    dsl.configuration(), TEST_BIGINT.getSchema().getName(),
                    TEST_BIGINT.getName()));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + TEST_BIGINT.getSchema().getName() + "." + TEST_BIGINT.getName()
                    + " TO " + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * A primary-key run resolves the key and records no filepath; a blocks run
     * records the heap filepath; an ordered run stores the index descriptor.
     */
    @Test
    void resolvesTheCreationKeyPerStrategy() {
        ResolveMigrationCreationKey primary = Routines.resolveMigrationCreationKey(
                dsl.configuration(), schema(TEST_BIGINT), name(TEST_BIGINT),
                ChunkingStrategy.primary_key, null, false);
        assertArrayEquals(new String[]{"id"}, primary.getOKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, primary.getOKeyKinds());
        assertNull(primary.getORelationFilepath());
        assertNull(primary.getOOrderedKeyIndex());

        ResolveMigrationCreationKey blocks = Routines.resolveMigrationCreationKey(
                dsl.configuration(), schema(TEST_BIGINT), name(TEST_BIGINT),
                ChunkingStrategy.blocks, null, false);
        assertNotNull(blocks.getORelationFilepath());

        ResolveMigrationCreationKey ordered = Routines.resolveMigrationCreationKey(
                dsl.configuration(), "dml_utils_fixtures", "test_ordered_key",
                ChunkingStrategy.ordered_key, "test_ordered_key_created_at_id_idx", false);
        assertArrayEquals(new String[]{"created_at", "id"}, ordered.getOKeyColumns());
        assertArrayEquals(new String[]{"timestamptz", "bigint"}, ordered.getOKeyKinds());
        assertEquals("test_ordered_key_created_at_id_idx", ordered.getOOrderedKeyIndex());
        assertArrayEquals(ordered.getOKeyColumns(), ordered.getOOrderedKeyColumns());
        assertArrayEquals(ordered.getOKeyKinds(), ordered.getOOrderedKeyKinds());
    }

    /**
     * A partition-wise blocks run leaves the run-level filepath NULL, while a
     * partition-wise ordered run records the parent descriptor.
     */
    @Test
    void resolvesPartitionWiseCreationKeys() {
        ResolveMigrationCreationKey blocks = Routines.resolveMigrationCreationKey(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range",
                ChunkingStrategy.blocks, null, true);
        assertNull(blocks.getORelationFilepath());

        ResolveMigrationCreationKey ordered = Routines.resolveMigrationCreationKey(
                dsl.configuration(), PARTITION_SCHEMA, "test_partitioned_range",
                ChunkingStrategy.ordered_key, "test_partitioned_range_ordered_key_idx", true);
        assertArrayEquals(new String[]{"id"}, ordered.getOKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, ordered.getOKeyKinds());
        assertEquals("test_partitioned_range_ordered_key_idx",
                ordered.getOOrderedKeyIndex());
    }

    /**
     * The manifest snapshot inserts one pending row per storage leaf and records
     * the blocks filepaths.
     */
    @Test
    void snapshotsThePartitionManifest() throws Exception {
        long runId = insertRunRow(ChunkingStrategy.blocks, true, 2);
        Routines.snapshotPartitionLeafManifest(dsl.configuration(), runId,
                PARTITION_SCHEMA, "test_partitioned_range", ChunkingStrategy.blocks, null);

        List<MigrationRunLeafRecord> leaves = dsl.selectFrom(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .orderBy(MIGRATION_RUN_LEAF.LEAF_ORDINAL)
                .fetch();
        assertEquals(3, leaves.size());
        for (MigrationRunLeafRecord leaf : leaves) {
            assertEquals("pending", leaf.getState());
            assertNotNull(leaf.getRelationFilepath());
        }
    }

    /**
     * The manifest snapshot enforces the caller's SELECT privilege on every leaf.
     */
    @Test
    void manifestSnapshotRejectsAnUnreadableLeaf() throws Exception {
        long runId = insertRunRow(ChunkingStrategy.primary_key, true, 2);
        executeAsOwner("REVOKE SELECT ON " + PARTITION_SCHEMA
                + ".test_partitioned_range_low FROM " + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> Routines.snapshotPartitionLeafManifest(
                    dsl.configuration(), runId, PARTITION_SCHEMA,
                    "test_partitioned_range", ChunkingStrategy.primary_key, null));
        } finally {
            executeAsOwner("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON "
                    + PARTITION_SCHEMA + ".test_partitioned_range_low TO "
                    + TEST_USER + ", dml_utils_caller");
        }
    }

    /**
     * The run row is the authority for the driving table and chunking strategy:
     * a direct caller cannot attach another relation's leaves or choose a
     * different strategy, and a second snapshot for the same run is rejected.
     */
    @Test
    void manifestSnapshotRejectsAMismatchedRunDescriptor() {
        long runId = insertRunRow(ChunkingStrategy.primary_key, true, 2);

        assertSqlState("22023", () -> Routines.snapshotPartitionLeafManifest(
                dsl.configuration(), runId, PARTITION_SCHEMA, "test_partitioned_multi",
                ChunkingStrategy.primary_key, null));
        assertSqlState("22023", () -> Routines.snapshotPartitionLeafManifest(
                dsl.configuration(), runId, PARTITION_SCHEMA, "test_partitioned_range",
                ChunkingStrategy.blocks, null));
        assertSqlState("22023", () -> Routines.snapshotPartitionLeafManifest(
                dsl.configuration(), runId, PARTITION_SCHEMA, "test_partitioned_range",
                ChunkingStrategy.ordered_key, "test_partitioned_range_ordered_key_idx"));

        Routines.snapshotPartitionLeafManifest(dsl.configuration(), runId,
                PARTITION_SCHEMA, "test_partitioned_range", ChunkingStrategy.primary_key, null);
        assertSqlState("55000", () -> Routines.snapshotPartitionLeafManifest(
                dsl.configuration(), runId, PARTITION_SCHEMA, "test_partitioned_range",
                ChunkingStrategy.primary_key, null));
    }

    /**
     * The single-relation helper inserts the contiguous boundaries and records
     * the totals.
     */
    @Test
    void populatesSingleRelationBoundaries() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = insertRunRow(ChunkingStrategy.primary_key, false, 2);

        Routines.populateSingleRelationBoundaries(dsl.configuration(), runId,
                schema(TEST_BIGINT), name(TEST_BIGINT), ChunkingStrategy.primary_key,
                new String[]{"id"}, new String[]{"bigint"}, 2);

        assertEquals(4, dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId)), "three starts plus the terminal");
        assertEquals(3L, dsl.select(MIGRATION_RUN.TOTAL_CHUNKS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_CHUNKS));
        assertNotNull(dsl.select(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT));

        // A repeat call is rejected instead of moving the milestone or hitting a
        // raw duplicate key from the boundary INSERT.
        assertSqlState("55000", () -> Routines.populateSingleRelationBoundaries(
                dsl.configuration(), runId, schema(TEST_BIGINT), name(TEST_BIGINT),
                ChunkingStrategy.primary_key, new String[]{"id"}, new String[]{"bigint"}, 2));
    }

    private long insertRunRow(ChunkingStrategy chunkBy, boolean partitionWise, int chunkSize) {
        String table = partitionWise ? "test_partitioned_range" : name(TEST_BIGINT);
        String tableSchema = partitionWise ? PARTITION_SCHEMA : schema(TEST_BIGINT);
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT,
                        MIGRATION_RUN.CHUNK_SIZE, MIGRATION_RUN.CHUNK_BY,
                        MIGRATION_RUN.PARTITION_WISE, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.STARTED_AT)
                .values(uniqueLabel("creation-helper"), TEMPLATE, chunkSize, chunkBy,
                        partitionWise, 1, tableSchema, table, OffsetDateTime.now())
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }
}
