package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.format_boundary_population_sql}: the descriptor
 * fragments are substituted into the single-relation boundary statement and the
 * resulting statement inserts the expected starts and terminal boundary.
 */
class FormatBoundaryPopulationSqlTest extends PostgresTestBase {

    private static final String PACK =
            "ROW(ARRAY[id1::bigint]::bigint[], NULL::text[], NULL::uuid[],"
                    + " NULL::timestamptz[])::dml_utils_data.migration_key";

    /**
     * Clears the fixture and run metadata between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndBigint();
    }

    /**
     * A NULL run id, a non-positive chunk size or a blank order-by is rejected
     * before rendering, instead of producing a statement that fails later.
     */
    @Test
    void rejectsInvalidRunInputs() {
        assertSqlState("22023", () -> Routines.formatBoundaryPopulationSql(
                dsl.configuration(), "id AS id1", "id", schema(TEST_BIGINT),
                name(TEST_BIGINT), "id1", PACK, "id1 DESC", null, 2L));
        assertSqlState("22023", () -> Routines.formatBoundaryPopulationSql(
                dsl.configuration(), "id AS id1", "id", schema(TEST_BIGINT),
                name(TEST_BIGINT), "id1", PACK, "id1 DESC", 42L, 0L));
        assertSqlState("22023", () -> Routines.formatBoundaryPopulationSql(
                dsl.configuration(), "id AS id1", " \t", schema(TEST_BIGINT),
                name(TEST_BIGINT), "id1", PACK, "id1 DESC", 42L, 2L));
        assertSqlState("22023", () -> Routines.formatBoundaryPopulationSql(
                dsl.configuration(), "id AS id1", "id", schema(TEST_BIGINT),
                name(TEST_BIGINT), "id1", PACK, " \t", 42L, 2L));
    }

    /**
     * The statement names the driving table and contains the supplied select,
     * order, id, pack and terminal fragments.
     */
    @Test
    void substitutesTheDescriptorFragments() {
        String sql = Routines.formatBoundaryPopulationSql(dsl.configuration(),
                "id AS id1", "id", schema(TEST_BIGINT), name(TEST_BIGINT),
                "id1", PACK, "id1 DESC", 42L, 2L);

        assertTrue(sql.contains("INSERT INTO dml_utils_data.migration_boundary"));
        assertTrue(sql.contains("dml_utils_fixtures.test_bigint"));
        assertTrue(sql.contains("id AS id1"));
        assertTrue(sql.contains("pg_catalog.row_number() OVER (ORDER BY id)"));
        assertTrue(sql.contains("id1 DESC"));
        assertTrue(sql.contains(PACK));
        assertTrue(sql.matches("(?s).*SELECT\\s+42,.*"),
                "both inserts select the run id 42");
        assertEquals(2, sql.split("SELECT\\s+42,", -1).length - 1,
                "the run id is substituted in both the starts and the terminal insert");
    }

    /**
     * Executing the formatted statement inserts the same contiguous starts and
     * terminal boundary as the builder it backs.
     */
    @Test
    void executesAsTheBoundaryStatement() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = insertRun(TEST_BIGINT);

        dsl.execute(Routines.formatBoundaryPopulationSql(dsl.configuration(),
                "id AS id1", "id", schema(TEST_BIGINT), name(TEST_BIGINT),
                "id1", PACK, "id1 DESC", runId, 2L));

        List<MigrationBoundaryRecord> boundaries = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
        assertEquals(4, boundaries.size(), "three starts plus the terminal");
        long[][] expected = {{0, 1}, {1, 3}, {2, 5}, {3, 5}};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], boundaries.get(i).getBoundaryNo().longValue());
            assertEquals(expected[i][1],
                    boundaries.get(i).getBoundaryId().getBigintValues()[0].longValue());
        }
    }
}
