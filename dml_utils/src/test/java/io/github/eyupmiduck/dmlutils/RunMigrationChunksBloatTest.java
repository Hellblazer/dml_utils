package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Measures what each entry point does to the vacuum horizon while a run is going.
 *
 * <p>{@code run_migration_chunks} is one SQL statement, so the coordinator's
 * snapshot pins the horizon for the whole run and a mid-run {@code VACUUM} can
 * remove nothing of what the committed chunks left behind.
 * {@code run_migration_chunks_committing} holds no snapshot between chunks, so
 * the same {@code VACUUM} reclaims them. Every assertion is on a number read
 * back: the coordinator's {@code pg_stat_activity.backend_xmin} and the counts
 * {@code VACUUM (VERBOSE)} prints.
 *
 * <p>Autovacuum is off on the driving table, and {@code i_sleep_ms} makes the run
 * slow enough that the {@code VACUUM} finishes inside one inter-chunk gap.
 */
class RunMigrationChunksBloatTest extends CommittingRunTestBase {

    private static final int ROWS = 12_000;
    private static final int CHUNK_SIZE = 1_000;
    private static final int SLEEP_MS = 400;
    private static final int CHUNKS_BEFORE_VACUUM = 5;
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";
    private static final Pattern VACUUM_TUPLES = Pattern.compile(
            "tuples: (\\d+) removed, (\\d+) remain, (\\d+) are dead but not yet removable");

    /**
     * Seeds the driving table and turns its autovacuum off, so only the test's own
     * VACUUM reclaims dead rows.
     */
    @BeforeEach
    void seedWithAutovacuumOff() throws SQLException {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.execute("INSERT INTO dml_utils_fixtures.test_bigint (id) SELECT g FROM generate_series(1, ?) AS g",
                ROWS);
        executeAsOwner("ALTER TABLE dml_utils_fixtures.test_bigint SET (autovacuum_enabled = false)");
    }

    /**
     * Restores the table's autovacuum setting for the rest of the class.
     */
    @AfterEach
    void restoreAutovacuum() throws SQLException {
        executeAsOwner("ALTER TABLE dml_utils_fixtures.test_bigint RESET (autovacuum_enabled)");
    }

    /**
     * The function holds the horizon: its coordinator keeps one backend_xmin for
     * the whole run, and a mid-run VACUUM removes nothing while every row the
     * committed chunks left behind is reported dead but not yet removable.
     */
    @Test
    void theFunctionPinsTheVacuumHorizonForTheWholeRun() throws Exception {
        Measurement measured = measure(true);

        System.out.printf("function  : %s%n", measured);
        assertTrue(measured.samples() >= 20, "the coordinator was sampled during the run: " + measured);
        assertEquals(0, measured.nullXminSamples(), "the function's coordinator always holds an xmin");
        assertEquals(1, measured.distinctXmins(), "and it is the same xmin for the whole run");
        assertEquals(0, measured.removed(), "VACUUM removes nothing mid-run");
        assertTrue(measured.notYetRemovable() >= measured.chunksBefore() * (long) CHUNK_SIZE,
                "every dead row of every committed chunk is pinned: " + measured);
    }

    /**
     * The procedure holds no snapshot between chunks: its coordinator's
     * backend_xmin advances with the run (and is null between statements), and a mid-run VACUUM leaves nothing of the committed
     * chunks not yet removable (at most the chunk in flight and any that committed
     * while the VACUUM ran).
     */
    @Test
    void theProcedureLetsVacuumReclaimCommittedChunksWhileTheRunGoes() throws Exception {
        Measurement measured = measure(false);

        System.out.printf("procedure : %s%n", measured);
        assertTrue(measured.samples() >= 20, "the coordinator was sampled during the run: " + measured);
        assertTrue(measured.nullXminSamples() > 0 || measured.distinctXmins() > 1,
                "the coordinator's xmin is null or advances with the run, it does not stay put: "
                        + measured);
        assertTrue(measured.notYetRemovable()
                        <= (measured.chunksAfter() - measured.chunksBefore() + 1L) * CHUNK_SIZE,
                "no dead row of a chunk committed before the VACUUM is pinned: " + measured);
        assertTrue(measured.removed() > 0, "VACUUM reclaimed rows mid-run: " + measured);
    }

    /**
     * Runs a slow migration, VACUUMs the driving table once {@value #CHUNKS_BEFORE_VACUUM}
     * chunks have committed, and samples the coordinator's backend_xmin throughout.
     *
     * @param function true to run the function, false to CALL the procedure
     * @return the readings
     */
    private Measurement measure(boolean function) throws Exception {
        String label = uniqueLabel(function ? "bloat-function" : "bloat-procedure");
        Call call = Call.of(TEMPLATE, schema(), name(), label, CHUNK_SIZE).sleepMs(SLEEP_MS);
        List<String> xmins = new ArrayList<>();
        int nulls = 0;
        long before;
        long after;
        VacuumReading vacuum;

        try (Connection coordinator = openTestConnection()) {
            int pid = pid(coordinator);
            CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
                try {
                    if (function) {
                        callFunction(coordinator, call);
                    } else {
                        callCommitting(coordinator, call);
                    }
                } catch (SQLException error) {
                    throw new IllegalStateException(error);
                }
            });

            // Sample until the chunks have committed, then VACUUM once. A call that
            // fails early surfaces its error here instead of leaving this loop waiting.
            while (!run.isDone() && completedChunks(label) < CHUNKS_BEFORE_VACUUM) {
                nulls += sample(pid, xmins);
                Thread.sleep(25);
            }
            if (run.isDone()) {
                run.get();
                fail("the run ended before " + CHUNKS_BEFORE_VACUUM + " chunks had committed");
            }
            before = completedChunks(label);
            vacuum = vacuum();
            after = completedChunks(label);

            // Keep sampling to the end of the run.
            while (!run.isDone()) {
                nulls += sample(pid, xmins);
                Thread.sleep(25);
            }
            run.get(60, TimeUnit.SECONDS);
        }
        assertEquals(ROWS, payloadCount(TEST_BIGINT, "done"), "the run processed every row");
        return new Measurement(xmins, nulls, before, after, vacuum.removed(), vacuum.notYetRemovable());
    }

    /**
     * Samples the coordinator's backend_xmin once. A null xmin is counted, a
     * non-null one is appended.
     *
     * @return 1 when the sampled xmin was null, else 0
     */
    private int sample(int pid, List<String> xmins) {
        var row = dsl.fetchOne("SELECT backend_xmin::text FROM pg_catalog.pg_stat_activity WHERE pid = ? AND state = 'active'",
                pid);
        if (row == null) {
            return 0;
        }
        String xmin = row.get(0, String.class);
        if (xmin == null) {
            return 1;
        }
        xmins.add(xmin);
        return 0;
    }

    private long completedChunks(String label) {
        Long completed = dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED).from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label)).fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED);
        return completed == null ? 0 : completed;
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * VACUUMs the driving table as its owner and reads the tuple counts from the
     * verbose output.
     */
    private VacuumReading vacuum() throws SQLException {
        try (Connection owner = openOwnerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("VACUUM (VERBOSE) dml_utils_fixtures.test_bigint");
            for (SQLWarning warning = statement.getWarnings(); warning != null;
                 warning = warning.getNextWarning()) {
                Matcher matcher = VACUUM_TUPLES.matcher(warning.getMessage());
                if (warning.getMessage().contains("test_bigint\"") && matcher.find()) {
                    return new VacuumReading(Long.parseLong(matcher.group(1)),
                            Long.parseLong(matcher.group(3)));
                }
            }
        }
        return fail("VACUUM (VERBOSE) printed no tuple counts for the driving table");
    }

    private record VacuumReading(long removed, long notYetRemovable) {
    }

    /**
     * What one run showed.
     *
     * @param xmins           the non-null coordinator backend_xmin samples
     * @param nullXminSamples how many samples saw a null backend_xmin
     * @param chunksBefore    chunks committed when the VACUUM started
     * @param chunksAfter     chunks committed when it ended
     * @param removed         dead rows the VACUUM removed
     * @param notYetRemovable dead rows the VACUUM could not remove
     */
    private record Measurement(List<String> xmins, int nullXminSamples, long chunksBefore,
                               long chunksAfter, long removed, long notYetRemovable) {

        long samples() {
            return xmins.size() + (long) nullXminSamples;
        }

        long distinctXmins() {
            return xmins.stream().distinct().count();
        }

        @Override
        public String toString() {
            return "xmin samples=" + samples() + " non-null=" + xmins.size() + " null=" + nullXminSamples
                    + " distinct non-null=" + distinctXmins()
                    + " chunks committed before/after VACUUM=" + chunksBefore + "/" + chunksAfter
                    + " VACUUM removed=" + removed + " not yet removable=" + notYetRemovable;
        }
    }
}
