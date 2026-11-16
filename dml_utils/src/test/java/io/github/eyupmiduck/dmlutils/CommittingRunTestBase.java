package io.github.eyupmiduck.dmlutils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared call helpers for the tests of {@code dml_utils.run_migration_chunks_committing}.
 *
 * <p>The procedure COMMITs, so a test must CALL it on a plain autocommit JDBC
 * connection: the jOOQ routine wrappers and the shared {@code dsl} would run it
 * inside a transaction block, where its first COMMIT fails.
 */
abstract class CommittingRunTestBase extends PostgresTestBase {

    /**
     * One call's inputs, so a test states only what differs from the defaults.
     *
     * @param template  the chunk SQL template
     * @param schema    the driving table's schema
     * @param table     the driving table
     * @param label     the run label
     * @param chunkSize the chunk size
     * @param threads   the worker count
     * @param chunkBy   the chunking strategy
     * @param sleepMs   the per-chunk delay
     * @param index     the ordered-key index, or null
     * @param partition whether the run is partition-wise
     */
    record Call(String template, String schema, String table, String label, int chunkSize,
                int threads, String chunkBy, int sleepMs, String index, boolean partition) {

        /**
         * A primary-key call with one thread and no delay.
         *
         * @param template  the chunk SQL template
         * @param schema    the driving table's schema
         * @param table     the driving table
         * @param label     the run label
         * @param chunkSize the chunk size
         * @return the call
         */
        static Call of(String template, String schema, String table, String label, int chunkSize) {
            return new Call(template, schema, table, label, chunkSize, 1, "primary_key", 0, null,
                    false);
        }

        Call threads(int value) {
            return new Call(template, schema, table, label, chunkSize, value, chunkBy, sleepMs,
                    index, partition);
        }

        Call chunkBy(String value) {
            return new Call(template, schema, table, label, chunkSize, threads, value, sleepMs,
                    index, partition);
        }

        Call sleepMs(int value) {
            return new Call(template, schema, table, label, chunkSize, threads, chunkBy, value,
                    index, partition);
        }

        Call index(String value) {
            return new Call(template, schema, table, label, chunkSize, threads, chunkBy, sleepMs,
                    value, partition);
        }

        Call partitionWise() {
            return new Call(template, schema, table, label, chunkSize, threads, chunkBy, sleepMs,
                    index, true);
        }

        /**
         * The argument list shared by the function and the procedure, as SQL.
         *
         * @return the arguments, in signature order
         */
        String arguments() {
            return String.join(", ",
                    quote(template), quote(schema), quote(table), quote(label),
                    Integer.toString(chunkSize), Integer.toString(threads), "'t'",
                    quote(chunkBy) + "::dml_utils_data.chunking_strategy", "1",
                    Integer.toString(sleepMs), index == null ? "NULL" : quote(index),
                    Boolean.toString(partition));
        }
    }

    /**
     * Executes one statement as the schema owner, for setup the test role may not do.
     *
     * @param sql the statement
     * @throws SQLException if it fails
     */
    protected void executeAsOwner(String sql) throws SQLException {
        try (Connection connection = openOwnerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Quotes a test string as a SQL literal.
     *
     * @param value the text
     * @return the literal
     */
    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * CALLs the committing procedure on the given autocommit connection.
     *
     * @param connection an autocommit connection of the test role
     * @param call       the call's inputs
     * @throws SQLException if the procedure fails
     */
    static void callCommitting(Connection connection, Call call) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CALL dml_utils.run_migration_chunks_committing(" + call.arguments() + ")");
        }
    }

    /**
     * Runs the function on the given connection, as the statement a caller would
     * write.
     *
     * @param connection a connection of the test role
     * @param call       the call's inputs
     * @throws SQLException if the function fails
     */
    static void callFunction(Connection connection, Call call) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT dml_utils.run_migration_chunks(" + call.arguments() + ")");
        }
    }

    /**
     * Returns the backend pid of a connection, so a test can terminate it.
     */
    protected static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Waits until the run for the label has completed at least the given number of
     * chunks.
     */
    protected void awaitCompletedChunks(String label, long chunks) throws InterruptedException {
        for (int attempt = 0; attempt < 600; attempt++) {
            Long completed = dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED).from(MIGRATION_RUN)
                    .where(MIGRATION_RUN.LABEL.eq(label)).fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED);
            if (completed != null && completed >= chunks) {
                return;
            }
            Thread.sleep(50);
        }
        fail("the run did not complete " + chunks + " chunks");
    }

    /**
     * Waits until no chunk worker is left running, so a resumed run starts from
     * committed state.
     */
    protected void awaitNoChunkWorkers() throws InterruptedException {
        for (int attempt = 0; attempt < 600; attempt++) {
            Integer running = dsl.fetchOne(
                            "SELECT count(*)::int FROM pg_catalog.pg_stat_activity"
                                    + " WHERE pid <> pg_backend_pid()"
                                    + " AND query LIKE '%dml_utils_lib.process_migration_chunk%'"
                                    + " AND state <> 'idle'")
                    .get(0, Integer.class);
            if (running != null && running == 0) {
                return;
            }
            Thread.sleep(50);
        }
        fail("a chunk worker is still running");
    }
}
