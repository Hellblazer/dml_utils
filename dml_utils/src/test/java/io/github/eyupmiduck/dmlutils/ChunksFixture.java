package io.github.eyupmiduck.dmlutils;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Seeds the nexus.chunks-shaped fixtures, {@code test_chunks_four} and its
 * two-level partitioned twin {@code test_chunks_partitioned}: a four-column
 * primary key (tenant_id text, collection text, chash bytea, embedding_model
 * text) with the bytea column third.
 *
 * <p>Every (model, tenant, collection) combination holds the same set of hashes:
 * 20 md5-derived 16-byte values plus keys that stress unsigned byte order (the
 * empty key, bytes either side of the sign bit, a prefix and its extension). The
 * set is deterministic, so a run's chunk boundaries repeat from one run to the
 * next.
 */
final class ChunksFixture {

    static final String SCHEMA = "dml_utils_fixtures";
    static final String PLAIN = "test_chunks_four";
    static final String PARTITIONED = "test_chunks_partitioned";
    static final String PLAIN_ORDERED_INDEX = "test_chunks_four_model_idx";
    static final String PARTITIONED_ORDERED_INDEX = "test_chunks_partitioned_model_idx";

    /**
     * The embedding models, the first-level partition values.
     */
    static final List<String> MODELS = List.of("m1", "m2");

    /**
     * The tenants; the third has no partition of its own and lands in the
     * default leaf under its model.
     */
    static final List<String> TENANTS = List.of("t0", "t1", "t2");

    static final List<String> COLLECTIONS = List.of("a", "b");

    /**
     * The hashes of every combination: 20 derived plus 5 edge keys.
     */
    static final int HASHES = 25;

    /**
     * The rows of either table once seeded.
     */
    static final int ROWS = MODELS.size() * TENANTS.size() * COLLECTIONS.size() * HASHES;

    /**
     * The storage leaves of the partitioned fixture, in manifest order: the
     * default leaves first (the partition bound {@code DEFAULT} sorts before
     * {@code FOR VALUES}), then each tenant's leaves by model.
     */
    static final List<String> PARTITIONED_LEAVES = List.of(
            "test_chunks_partitioned_m1_other", "test_chunks_partitioned_m2_other",
            "test_chunks_partitioned_m1_t0", "test_chunks_partitioned_m2_t0",
            "test_chunks_partitioned_m1_t1", "test_chunks_partitioned_m2_t1");

    /**
     * Selects a row's whole key as text, for comparing the rows a run's chunks
     * select with the rows of the table. The alias is {@code t}.
     */
    static final String ROW_EXPRESSION = "t.tenant_id || '/' || t.collection || '/'"
            + " || pg_catalog.encode(t.chash, 'hex') || '/' || t.embedding_model";

    private ChunksFixture() {
    }

    /**
     * Seeds {@link #ROWS} rows into one of the two fixture tables.
     *
     * @param dsl   the test role's context
     * @param table {@link #PLAIN} or {@link #PARTITIONED}
     */
    static void seed(DSLContext dsl, String table) {
        dsl.execute("INSERT INTO " + SCHEMA + "." + table
                + " (tenant_id, collection, chash, embedding_model)"
                + " SELECT t, c, h.chash, m"
                + " FROM pg_catalog.unnest(ARRAY['m1', 'm2']) AS m,"
                + "      pg_catalog.unnest(ARRAY['t0', 't1', 't2']) AS t,"
                + "      pg_catalog.unnest(ARRAY['a', 'b']) AS c,"
                + "      (SELECT pg_catalog.decode(pg_catalog.md5(g::text)"
                + "                                || pg_catalog.md5(g::text || 'x'), 'hex') AS chash"
                + "       FROM pg_catalog.generate_series(1, 20) AS g"
                + "       UNION ALL SELECT pg_catalog.decode(e, 'hex')"
                + "                 FROM pg_catalog.unnest(ARRAY['', '00', '00ff', 'ff', 'ffff']) AS e)"
                + "      AS h");
    }

    /**
     * Returns every row of a seeded table as {@link #ROW_EXPRESSION} text, sorted.
     *
     * @param dsl   the test role's context
     * @param table {@link #PLAIN} or {@link #PARTITIONED}
     * @return the sorted row texts
     */
    static List<String> allRows(DSLContext dsl, String table) {
        List<String> rows = new ArrayList<>(dsl.fetch("SELECT " + ROW_EXPRESSION + " AS r FROM "
                + SCHEMA + "." + table + " AS t").getValues("r", String.class));
        Collections.sort(rows);
        return rows;
    }
}
