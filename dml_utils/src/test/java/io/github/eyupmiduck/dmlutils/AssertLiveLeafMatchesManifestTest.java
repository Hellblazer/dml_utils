package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ManifestLeafIdentity;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies {@code dml_utils_lib.assert_live_leaf_matches_manifest}: a matching
 * live relation passes, while a different OID, partition bound, name, heap
 * filepath or relation kind fails closed with {@code 55000}.
 */
class AssertLiveLeafMatchesManifestTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String TABLE = "test_partitioned_range";

    /**
     * Empties the range fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A key and a blocks leaf identity both match the live relation.
     */
    @Test
    void acceptsAMatchingLeaf() {
        long keyRun = populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        ManifestLeafIdentity key = identity(keyRun);
        assertDoesNotThrow(() -> assertLeaf(key));

        long blocksRun = populatePartitionWise("live-leaf-blocks", TABLE, TEMPLATE, ChunkingStrategy.blocks, 2, null);
        ManifestLeafIdentity blocks = identity(blocksRun);
        assertDoesNotThrow(() -> assertLeaf(blocks));
    }

    /**
     * A different OID, partition bound or name is rejected.
     */
    @Test
    void rejectsAReplacedReboundOrMissingLeaf() {
        ManifestLeafIdentity leaf = identity(populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null));

        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), leaf.getOChunkBy(), leaf.getOLeafSchemaName(),
                leaf.getOLeafName(), leaf.getOLeafOid() + 1, leaf.getOLeafBound(),
                leaf.getORelationFilepath()));
        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), leaf.getOChunkBy(), leaf.getOLeafSchemaName(),
                leaf.getOLeafName(), leaf.getOLeafOid(),
                "FOR VALUES FROM ('0') TO ('99')", leaf.getORelationFilepath()));
        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), leaf.getOChunkBy(), leaf.getOLeafSchemaName(),
                "no_such_leaf", leaf.getOLeafOid(), leaf.getOLeafBound(),
                leaf.getORelationFilepath()));
    }

    /**
     * A blocks run rejects a changed heap filepath, and a key run rejects a
     * recorded filepath.
     */
    @Test
    void rejectsAChangedOrUnexpectedFilepath() {
        ManifestLeafIdentity blocks = identity(populatePartitionWise("live-leaf-blocks", TABLE, TEMPLATE, ChunkingStrategy.blocks, 2, null));
        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), blocks.getOChunkBy(), blocks.getOLeafSchemaName(),
                blocks.getOLeafName(), blocks.getOLeafOid(), blocks.getOLeafBound(),
                "base/1/999"));

        ManifestLeafIdentity key = identity(populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null));
        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), key.getOChunkBy(), key.getOLeafSchemaName(),
                key.getOLeafName(), key.getOLeafOid(), key.getOLeafBound(),
                "base/1/999"));
    }

    /**
     * A relation that is not an ordinary table (the parent itself) is rejected.
     */
    @Test
    void rejectsANonTableRelation() {
        long parentOid = dsl.fetchOne("SELECT ?::regclass::oid",
                        PARTITION_SCHEMA + "." + TABLE)
                .get(0, Long.class);

        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), ChunkingStrategy.primary_key,
                PARTITION_SCHEMA, TABLE, parentOid, null, null));
    }


    /**
     * A NULL chunk strategy is rejected with {@code 22023} instead of skipping
     * both filepath guards.
     */
    @Test
    void rejectsANullChunkStrategy() {
        ManifestLeafIdentity leaf = identity(
                populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE,
                        ChunkingStrategy.primary_key, 2, null));

        assertSqlState("22023", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), null, leaf.getOLeafSchemaName(),
                leaf.getOLeafName(), leaf.getOLeafOid(), leaf.getOLeafBound(),
                leaf.getORelationFilepath()));
    }

    /**
     * A NULL schema/name is a caller argument error ({@code 22023}), not the
     * opaque EXECUTE failure a NULL format() argument would produce.
     */
    @Test
    void rejectsANullLeafName() {
        ManifestLeafIdentity leaf = identity(
                populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE,
                        ChunkingStrategy.primary_key, 2, null));

        assertSqlState("22023", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), leaf.getOChunkBy(), null, leaf.getOLeafName(),
                leaf.getOLeafOid(), leaf.getOLeafBound(), leaf.getORelationFilepath()));
        assertSqlState("22023", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), leaf.getOChunkBy(), leaf.getOLeafSchemaName(), null,
                leaf.getOLeafOid(), leaf.getOLeafBound(), leaf.getORelationFilepath()));
    }

    /**
     * An index occupying the leaf name is not lockable as a table; the lock is
     * skipped and the relkind guard reports the documented {@code 55000}.
     */
    @Test
    void reportsAnIndexOccupyingTheLeafName() {
        long indexOid = relationOid(
                PARTITION_SCHEMA + ".test_partitioned_range_ordered_key_idx");

        assertSqlState("55000", () -> Routines.assertLiveLeafMatchesManifest(
                dsl.configuration(), ChunkingStrategy.primary_key, PARTITION_SCHEMA,
                "test_partitioned_range_ordered_key_idx", indexOid, null, null));
    }

    /**
     * A concurrent rewrite holding the leaf blocks the {@code NOWAIT} lock; that
     * surfaces as the documented {@code 55000}, not {@code 55P03}.
     */
    @Test
    void reportsAConcurrentModificationAsTheDocumentedState() throws Exception {
        long runId = populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);
        ManifestLeafIdentity leaf = identity(runId,
                leafOrdinal(runId, PARTITIONED_RANGE_MID));

        try (Connection blocker = openOwnerConnection()) {
            DSLContext blockerDsl = DSL.using(blocker, SQLDialect.POSTGRES);
            RuntimeException rollback = new RollbackTransaction();
            try {
                blockerDsl.transaction(config -> {
                    DSLContext tx = DSL.using(config);
                    tx.execute("LOCK TABLE " + PARTITION_SCHEMA + "." + PARTITIONED_RANGE_MID
                            + " IN ACCESS EXCLUSIVE MODE");
                    assertSqlState("55000", () -> assertLeaf(leaf));
                    throw rollback;
                });
                fail("the transaction should have been rolled back");
            } catch (RuntimeException error) {
                if (error != rollback && error.getCause() != rollback) {
                    throw error;
                }
            }
        }
    }

    /**
     * A leaf that has actually been detached fails closed: its live partition
     * bound is NULL, so the stored bound no longer matches.
     */
    @Test
    void rejectsADetachedLeaf() throws Exception {
        long runId = populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);
        ManifestLeafIdentity leaf = identity(runId,
                leafOrdinal(runId, PARTITIONED_RANGE_MID));

        executeAsOwner("ALTER TABLE " + PARTITION_SCHEMA + "." + TABLE
                + " DETACH PARTITION " + PARTITION_SCHEMA + ".test_partitioned_range_mid");
        try {
            assertSqlState("55000", () -> assertLeaf(leaf));
        } finally {
            restoreMidPartition();
        }
    }

    /**
     * The ACCESS SHARE lock is held until the caller's transaction ends, so a
     * concurrent DETACH waits and fails with {@code 55P03} under a lock timeout
     * instead of racing the assertion.
     */
    @Test
    void holdsAccessShareUntilTheTransactionEnds() throws Exception {
        long runId = populatePartitionWise("live-leaf-primary_key", TABLE, TEMPLATE,
                ChunkingStrategy.primary_key, 2, null);
        ManifestLeafIdentity leaf = identity(runId,
                leafOrdinal(runId, PARTITIONED_RANGE_MID));

        try (Connection asserting = openOwnerConnection();
             Connection detaching = openOwnerConnection()) {
            DSLContext assertingDsl = DSL.using(asserting, SQLDialect.POSTGRES);
            DSLContext detachingDsl = DSL.using(detaching, SQLDialect.POSTGRES);
            RuntimeException rollback = new RollbackTransaction();
            try {
                assertingDsl.transaction(config -> {
                    DSLContext tx = DSL.using(config);
                    assertLeaf(tx, leaf);

                    detachingDsl.execute("SET lock_timeout = '250ms'");
                    assertSqlState("55P03", () -> detachingDsl.execute(
                            "ALTER TABLE " + PARTITION_SCHEMA + "." + TABLE
                                    + " DETACH PARTITION " + PARTITION_SCHEMA + "."
                                    + PARTITIONED_RANGE_MID));
                    throw rollback;
                });
                fail("the transaction should have been rolled back");
            } catch (RuntimeException error) {
                if (error != rollback && error.getCause() != rollback) {
                    throw error;
                }
            } finally {
                // If the lock guarantee regresses, the DETACH above succeeds; put
                // the fixture back so later tests in the class see three leaves.
                restoreMidPartition();
            }
        }
    }

    private ManifestLeafIdentity identity(long runId) {
        return identity(runId, 1);
    }

    private ManifestLeafIdentity identity(long runId, int ordinal) {
        return Routines.manifestLeafIdentity(dsl.configuration(), runId, ordinal);
    }

    private void assertLeaf(ManifestLeafIdentity leaf) {
        assertLeaf(dsl, leaf);
    }

    private void assertLeaf(DSLContext context, ManifestLeafIdentity leaf) {
        Routines.assertLiveLeafMatchesManifest(context.configuration(),
                leaf.getOChunkBy(), leaf.getOLeafSchemaName(), leaf.getOLeafName(),
                leaf.getOLeafOid(), leaf.getOLeafBound(),
                leaf.getORelationFilepath());
    }

    /**
     * Sentinel thrown to roll back the lock-holding transaction.
     */
    private static final class RollbackTransaction extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RollbackTransaction() {
            super("roll back the lock-holding transaction");
        }
    }
}
