package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ManifestLeafIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
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
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
    }

    /**
     * A key run's three leaves expose their schema, name, OID and bound, with no
     * per-leaf filepath.
     */
    @Test
    void returnsAKeyLeafIdentity() {
        long runId = createRun(ChunkingStrategy.primary_key);

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
        long runId = createRun(ChunkingStrategy.blocks);

        ManifestLeafIdentity low = identity(runId, 1);
        assertEquals(ChunkingStrategy.blocks, low.getOChunkBy());
        assertNotNull(low.getORelationFilepath());
        assertEquals(dsl.fetchOne("SELECT pg_catalog.pg_relation_filepath(?::regclass)",
                        PARTITION_SCHEMA + ".test_partitioned_range_low")
                .get(0, String.class), low.getORelationFilepath());
    }

    /**
     * A missing run or leaf ordinal raises {@code 55000}.
     */
    @Test
    void rejectsAMissingRunOrLeaf() {
        long runId = createRun(ChunkingStrategy.primary_key);
        assertSqlState("55000", () -> identity(-1L, 1));
        assertSqlState("55000", () -> identity(runId, 999));
    }

    private long createRun(ChunkingStrategy chunkBy) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                PARTITION_SCHEMA, TABLE, uniqueLabel("manifest-identity-" + chunkBy),
                TEMPLATE, 2, 1, chunkBy, "t", 0, null, true);
    }

    private ManifestLeafIdentity identity(long runId, int ordinal) {
        return Routines.manifestLeafIdentity(dsl.configuration(), runId, ordinal);
    }
}
