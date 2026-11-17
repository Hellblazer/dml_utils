package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@code dml_utils.set_migration_run_retries}: it updates the retry
 * policy of an unfinished run, accepts zero values, and leaves terminal or
 * missing runs alone.
 *
 * <p>This covers the stored policy. The behavioural retry test is added with
 * the coordinator retry loop ({@code dml-99x.17}).
 */
class SetMigrationRunRetriesTest extends PostgresTestBase {

    // The retry policy these runs are created with. They match the column
    // defaults, and the tests assert the setter leaves these explicit values
    // unchanged on a terminal run.
    private static final int RETRIES = 3;
    private static final int RETRY_BACKOFF_MS = 1000;

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Clears the fixture table and the migration metadata so each test starts
     * clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * The setter replaces both retry values on an unfinished run.
     */
    @Test
    void updatesAnUnfinishedRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("retries-update");
        populate(label);

        Routines.setMigrationRunRetries(dsl.configuration(), label, 5, 250);

        MigrationRunRecord run = run(runId(label));
        assertEquals(5, run.getMaxRetries(), "max_retries is updated");
        assertEquals(250, run.getRetryBackoffMs(), "retry_backoff_ms is updated");
    }

    /**
     * Zero is a valid policy: it disables retries and retries immediately.
     */
    @Test
    void acceptsZeroValues() {
        seedBigint(1);
        String label = uniqueLabel("retries-zero");
        populate(label);

        Routines.setMigrationRunRetries(dsl.configuration(), label, 0, 0);

        MigrationRunRecord run = run(runId(label));
        assertEquals(0, run.getMaxRetries());
        assertEquals(0, run.getRetryBackoffMs());
    }

    /**
     * A paused run is unfinished, so its retry policy can be adjusted; the pause
     * itself is untouched.
     */
    @Test
    void updatesAPausedRun() {
        seedBigint(1);
        String label = uniqueLabel("retries-paused");
        populate(label);
        assertNotNull(Routines.pauseMigrationRun(dsl.configuration(), label));

        Routines.setMigrationRunRetries(dsl.configuration(), label, 2, 50);

        MigrationRunRecord run = run(runId(label));
        assertEquals(2, run.getMaxRetries());
        assertEquals(50, run.getRetryBackoffMs());
        assertNotNull(run.getPausedAt(), "the pause is untouched");
    }

    /**
     * A missing run raises no_data_found.
     */
    @Test
    void rejectsAMissingRun() {
        assertSqlState("P0002", () -> Routines.setMigrationRunRetries(
                dsl.configuration(), uniqueLabel("retries-none"), 1, 1));
    }

    /**
     * A completed run is not updated.
     */
    @Test
    void doesNotUpdateACompletedRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("retries-completed");
        runToCompletion(label);
        assertNotNull(run(runId(label)).getCompletedAt(), "the run completed");

        assertSqlState("P0002", () -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, 9, 9));

        MigrationRunRecord run = run(runId(label));
        assertEquals(RETRIES, run.getMaxRetries(), "the created retry count is unchanged");
        assertEquals(RETRY_BACKOFF_MS, run.getRetryBackoffMs(),
                "the created retry backoff is unchanged");
    }

    /**
     * A cancelled run is not updated.
     */
    @Test
    void doesNotUpdateACancelledRun() {
        seedBigint(1);
        String label = uniqueLabel("retries-cancelled");
        populate(label);
        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));

        assertSqlState("P0002", () -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, 9, 9));

        MigrationRunRecord run = run(runId(label));
        assertEquals(RETRIES, run.getMaxRetries(), "the created retry count is unchanged");
        assertEquals(RETRY_BACKOFF_MS, run.getRetryBackoffMs(),
                "the created retry backoff is unchanged");
    }

    /**
     * An archived run is not updated.
     */
    @Test
    void doesNotUpdateAnArchivedRun() {
        seedBigint(1);
        String label = uniqueLabel("retries-archived");
        populate(label);
        assertNotNull(Routines.archiveMigrationRun(dsl.configuration(), label));

        assertSqlState("P0002", () -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, 9, 9));

        MigrationRunRecord run = run(runId(label));
        assertEquals(RETRIES, run.getMaxRetries(), "the created retry count is unchanged");
        assertEquals(RETRY_BACKOFF_MS, run.getRetryBackoffMs(),
                "the created retry backoff is unchanged");
    }

    /**
     * NULL and negative values are rejected by the non-negative-integer domain.
     */
    @Test
    void rejectsNullAndNegativeValues() {
        seedBigint(1);
        String label = uniqueLabel("retries-invalid");
        populate(label);

        assertDomainViolation(() -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, null, 1));
        assertDomainViolation(() -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, 1, null));
        assertDomainViolation(() -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, -1, 1));
        assertDomainViolation(() -> Routines.setMigrationRunRetries(
                dsl.configuration(), label, 1, -1));
    }

    /**
     * Creates the run and its boundaries without processing any chunk.
     *
     * @param label the run label
     * @return the new run id
     */
    private Long populate(String label) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_BIGINT), name(TEST_BIGINT), label,
                TEMPLATE, 1, 1, ChunkingStrategy.primary_key, "t", 0, null, false,
                RETRIES, RETRY_BACKOFF_MS);
    }

    /**
     * Runs the migration to completion.
     *
     * @param label the run label
     */
    private void runToCompletion(String label) {
        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false,
                RETRIES, RETRY_BACKOFF_MS);
    }

    /**
     * Reads the run row.
     *
     * @param runId the run id
     * @return the run record
     */
    private MigrationRunRecord run(long runId) {
        MigrationRunRecord row = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(row, () -> "no migration run " + runId);
        return row;
    }
}
