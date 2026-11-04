package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ManifestLeafIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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


    private ManifestLeafIdentity identity(long runId) {
        return Routines.manifestLeafIdentity(dsl.configuration(), runId, 1);
    }

    private void assertLeaf(ManifestLeafIdentity leaf) {
        Routines.assertLiveLeafMatchesManifest(dsl.configuration(), leaf.getOChunkBy(),
                leaf.getOLeafSchemaName(), leaf.getOLeafName(), leaf.getOLeafOid(),
                leaf.getOLeafBound(), leaf.getORelationFilepath());
    }
}
