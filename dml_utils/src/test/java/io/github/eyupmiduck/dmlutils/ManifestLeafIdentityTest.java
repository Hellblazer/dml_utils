package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ManifestLeafIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.manifest_leaf_identity}: the stored identity of a
 * manifest leaf is returned field by field for a key run and a blocks run, and a
 * missing run or leaf raises {@code 55000}.
 */
class ManifestLeafIdentityTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String TABLE = "test_partitioned_range";

    /**
     * Empties the range fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndRange();
    }

    /**
     * A key run's three leaves expose their schema, name, OID and bound, with no
     * per-leaf filepath.
     */
    @Test
    void returnsAKeyLeafIdentity() {
        long runId = populatePartitionWise("manifest-identity-primary_key", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);

        ManifestLeafIdentity low = identity(runId, 1);
        assertEquals(ChunkingStrategy.primary_key, low.getOChunkBy());
        assertEquals(PARTITION_SCHEMA, low.getOLeafSchemaName());
        assertEquals("test_partitioned_range_low", low.getOLeafName());
        assertNotNull(low.getOLeafOid());
        assertEquals("FOR VALUES FROM ('0') TO ('100')", low.getOLeafBound());
        assertNull(low.getORelationFilepath());
    }

    /**
     * A blocks run records the leaf's physical filepath.
     */
    @Test
    void returnsABlocksLeafIdentity() {
        long runId = populatePartitionWise("manifest-identity-blocks", TABLE, TEMPLATE, ChunkingStrategy.blocks, 2, null);

        ManifestLeafIdentity low = identity(runId, 1);
        assertEquals(ChunkingStrategy.blocks, low.getOChunkBy());
        assertNotNull(low.getORelationFilepath());
        assertEquals(dsl.fetchOne("SELECT pg_catalog.pg_relation_filepath(?::regclass)",
                        PARTITION_SCHEMA + ".test_partitioned_range_low")
                .get(0, String.class), low.getORelationFilepath());
    }

    /**
     * An unknown run raises {@code P0002}; a known run without the leaf ordinal
     * raises {@code 55000}.
     */
    @Test
    void rejectsAMissingRunOrLeaf() {
        long runId = populatePartitionWise("manifest-identity-primary_key", TABLE, TEMPLATE, ChunkingStrategy.primary_key, 2, null);
        assertSqlState("P0002", () -> identity(-1L, 1));
        assertSqlState("55000", () -> identity(runId, 999));
    }


}
