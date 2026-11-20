package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies {@code dml_utils_lib.assert_read_committed()} and the runner entry
 * points that call it: READ COMMITTED passes, REPEATABLE READ and SERIALIZABLE
 * raise {@code 0A000} before any run work happens.
 */
class AssertReadCommittedTest extends PostgresTestBase {

    /**
     * The default READ COMMITTED transaction passes.
     */
    @Test
    void acceptsReadCommitted() {
        assertDoesNotThrow(() -> inIsolation("read committed", tx ->
                Routines.assertReadCommitted(tx.configuration())));
    }

    /**
     * A REPEATABLE READ transaction is rejected with {@code 0A000}, because its
     * fixed snapshot would never observe the workers' autonomous commits.
     */
    @Test
    void rejectsRepeatableRead() {
        assertSqlState("0A000", () -> inIsolation("repeatable read", tx ->
                Routines.assertReadCommitted(tx.configuration())));
    }

    /**
     * A SERIALIZABLE transaction is rejected with {@code 0A000} for the same
     * reason.
     */
    @Test
    void rejectsSerializable() {
        assertSqlState("0A000", () -> inIsolation("serializable", tx ->
                Routines.assertReadCommitted(tx.configuration())));
    }

    /**
     * The coordinator fails before validating the template or creating a run when
     * the caller is not READ COMMITTED.
     */
    @Test
    void runMigrationChunksRejectsANonReadCommittedTransaction() {
        assertSqlState("0A000", () -> inIsolation("repeatable read", tx ->
                tx.execute("SELECT dml_utils.run_migration_chunks("
                        + "'SELECT 1', 'public', 'no_such_table', 'probe')")));
    }

    /**
     * The function-over-table wrapper fails at its entry point for the same
     * reason, before it resolves the user function.
     */
    @Test
    void runFunctionOverTableRejectsANonReadCommittedTransaction() {
        assertSqlState("0A000", () -> inIsolation("serializable", tx ->
                tx.execute("SELECT dml_utils.run_function_over_table("
                        + "'public', 'no_such_table', 'public', 'no_such_function')")));
    }

    /**
     * Run resolution fails before it looks up or creates a run.
     */
    @Test
    void resolveMigrationRunRejectsANonReadCommittedTransaction() {
        assertSqlState("0A000", () -> inIsolation("repeatable read", tx ->
                tx.execute("SELECT * FROM dml_utils_lib.resolve_migration_run("
                        + "i_sql_text => 'SELECT 1',"
                        + " i_driving_table_schema_name => 'public',"
                        + " i_driving_table_name => 'no_such_table',"
                        + " i_label => 'probe', i_chunk_size => 1, i_threads => 1,"
                        + " i_driving_table_alias => 't')")));
    }

    /**
     * The per-chunk sleep fails before its first slice, so it cannot silently
     * sleep through a pause or cancellation it cannot see.
     */
    @Test
    void sleepBetweenChunksRejectsANonReadCommittedTransaction() {
        assertSqlState("0A000", () -> inIsolation("repeatable read", tx ->
                tx.execute("SELECT dml_utils_lib.sleep_between_migration_chunks(1, 100)")));
    }

    /**
     * Partition-wise boundary calculation fails before reading the run row.
     */
    @Test
    void calculateBoundariesRejectsANonReadCommittedTransaction() {
        assertSqlState("0A000", () -> inIsolation("serializable", tx ->
                tx.execute("SELECT dml_utils_lib.calculate_migration_run_boundaries(1)")));
    }

    /**
     * Runs the action in a transaction set to the given isolation level.
     *
     * @param level  the PostgreSQL isolation level name
     * @param action the statements to run in that transaction
     */
    private void inIsolation(String level, Consumer<DSLContext> action) {
        dsl.transaction(config -> {
            DSLContext tx = DSL.using(config);
            tx.execute("SET TRANSACTION ISOLATION LEVEL " + level);
            action.accept(tx);
        });
    }
}
