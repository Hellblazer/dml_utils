package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNoPk.TEST_NO_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKeyUuid.TEST_ORDERED_KEY_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the metadata-privilege boundary: the caller can read migration
 * metadata and run chunk SQL as itself, but cannot write metadata directly.
 * Chunk SQL remains SECURITY INVOKER; metadata writes go through the
 * owner-owned helpers.
 */
class MetadataPrivilegeTest extends PostgresTestBase {

    private static final String ROLE_TEMPLATE =
            "UPDATE <driving_table> SET payload = current_user WHERE <chunking_clause>";

    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_ORDERED_KEY_UUID).execute();
    }

    /**
     * The caller can read metadata but has no direct DML on the migration
     * tables.
     */
    @Test
    void callerCannotWriteMetadataDirectly() throws Exception {
        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            assertNotNull(caller.fetchOne(
                    "SELECT count(*) FROM dml_utils_data.migration_run"));
            assertSqlState("42501", () -> caller.execute(
                    "UPDATE dml_utils_data.migration_run SET label = label"));
            assertSqlState("42501", () -> caller.execute(
                    "DELETE FROM dml_utils_data.migration_boundary"));
        }
    }

    /**
     * The invoker-owned worker runs the chunk SQL as the caller. The helper
     * writes the metadata, but the SQL itself sees current_user = caller.
     */
    @Test
    void chunkSqlRunsAsTheCaller() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("caller-role");

        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            Routines.runMigrationChunks(caller.configuration(), ROLE_TEMPLATE,
                    TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(), label,
                    2, 1, "t", ChunkingStrategy.primary_key, 1, 0, null);
        }

        assertEquals(3, dsl.fetchCount(TEST_BIGINT,
                        TEST_BIGINT.PAYLOAD.eq("dml_utils_caller")),
                "the chunk SQL should execute with the caller role, not the owner");
        assertTrue(runCompleted(label), "the run should complete");
    }

    /**
     * The caller can run an ordered-key chunk as itself, and an unsafe index is
     * still rejected with the resolver's SQLSTATE.
     */
    @Test
    void callerCanRunAnOrderedKeyChunk() throws Exception {
        for (int i = 1; i <= 4; i++) {
            dsl.insertInto(TEST_ORDERED_KEY_UUID,
                            TEST_ORDERED_KEY_UUID.ID, TEST_ORDERED_KEY_UUID.CREATED_AT)
                    .values(uuid(i), timestamp((i + 1) / 2))
                    .execute();
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("GRANT SELECT, UPDATE ON dml_utils_fixtures.test_ordered_key_uuid"
                    + " TO dml_utils_caller");
            statement.execute("GRANT SELECT ON dml_utils_fixtures.test_ordered_key"
                    + " TO dml_utils_caller");
        }

        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            Routines.runMigrationChunks(caller.configuration(), ROLE_TEMPLATE,
                    TEST_ORDERED_KEY_UUID.getSchema().getName(),
                    TEST_ORDERED_KEY_UUID.getName(), uniqueLabel("caller-ordered"),
                    2, 1, "t", ChunkingStrategy.ordered_key, 1, 0,
                    "test_ordered_key_uuid_created_at_id_idx");

            assertSqlState("0A000", () -> Routines.runMigrationChunks(
                    caller.configuration(), ROLE_TEMPLATE,
                    TEST_ORDERED_KEY.getSchema().getName(), TEST_ORDERED_KEY.getName(),
                    uniqueLabel("caller-ordered-unsafe"), 2, 1, "t",
                    ChunkingStrategy.ordered_key, 1, 0, "test_ordered_key_nonunique_idx"));
        }

        assertEquals(4, dsl.fetchCount(TEST_ORDERED_KEY_UUID,
                        TEST_ORDERED_KEY_UUID.PAYLOAD.eq("dml_utils_caller")),
                "the ordered-key chunk SQL should execute as the caller");
    }

    /**
     * Direct metadata DML in a chunk template fails; the run records the
     * failure and does not complete.
     */
    @Test
    void chunkTemplateCannotWriteMetadataDirectly() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("caller-metadata");
        String malicious = "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>;"
                + " UPDATE dml_utils_data.migration_run SET label = 'tampered'"
                + " WHERE label = '" + label + "'";

        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            assertSqlState("42501", () -> Routines.runMigrationChunks(
                    caller.configuration(), malicious, TEST_BIGINT.getSchema().getName(),
                    TEST_BIGINT.getName(), label, 2, 1, "t",
                    ChunkingStrategy.primary_key, 1, 0, null));
        }

        assertFalse(runCompleted(label), "the failed run must not be complete");
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "the tampering statement must not change the run label");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR,
                        MIGRATION_ERROR.RUN_ID.eq(runId(label))),
                "the chunk failure should be recorded by the owner-owned helper");
    }

    /**
     * The completion helper is idempotent: a second direct call fails without
     * incrementing the run's counter twice.
     */
    @Test
    void completeBoundaryDoesNotDoubleIncrement() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("complete-once");
        long runId = populate(label);

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, OffsetDateTime.now());
        assertEquals(1L, totalCompleted(runId));
        assertTrue(boundaryCompleted(runId, 0));

        assertSqlState("P0C02", () ->
                io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                        dsl.configuration(), runId, 0L, OffsetDateTime.now()));
        assertEquals(1L, totalCompleted(runId), "the failed second completion must not count");
    }

    /**
     * The run-completion helper refuses to complete a run before all its chunks
     * have completed, then succeeds once the counter reaches the total.
     */
    @Test
    void completeRunRequiresAllChunks() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("complete-run");
        long runId = populate(label);

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationRun(
                dsl.configuration(), runId);
        assertFalse(runCompleted(runId), "a run with pending chunks must not complete");

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, OffsetDateTime.now());
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 1L, OffsetDateTime.now());
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationRun(
                dsl.configuration(), runId);

        assertTrue(runCompleted(runId), "a run with all chunks completed should complete");
    }

    /**
     * Cancellation is a no-op for a completed run and must not mark its
     * boundaries cancelled after completion.
     */
    @Test
    void cancellationDoesNotTouchACompletedRun() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("cancel-completed");
        long runId = populate(label);
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 0L, OffsetDateTime.now());
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationBoundary(
                dsl.configuration(), runId, 1L, OffsetDateTime.now());
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationRun(
                dsl.configuration(), runId);
        assertTrue(runCompleted(runId));

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .markMigrationRunCancelled(dsl.configuration(), runId);

        assertTrue(runCompleted(runId), "the completed run must stay complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNotNull())),
                "a completed run must not gain cancelled boundaries");
    }

    /**
     * The caller can use the public metadata mutators even though it has no
     * direct DML; each mutator runs through an owner-owned SECURITY DEFINER
     * function.
     */
    @Test
    void callerCanUseMetadataMutators() throws Exception {
        seedBigint(1, 2, 3);
        dsl.execute("CREATE OR REPLACE FUNCTION public.caller_mark(bigint)"
                + " RETURNS void LANGUAGE plpgsql AS $$ BEGIN NULL; END $$");
        String label = uniqueLabel("caller-mutators");
        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);
            long runId = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                    .populateMigrationBoundaries(caller.configuration(),
                            TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(), label,
                            ROLE_TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null);

            Routines.setMigrationRunThreads(caller.configuration(), label, 2);
            Routines.setMigrationRunSleepMs(caller.configuration(), label, 5);
            Routines.setMigrationRunSqlText(caller.configuration(), label, ROLE_TEMPLATE);
            Routines.setMigrationRunFunction(caller.configuration(), label, PUBLIC_SCHEMA,
                    "caller_mark", null);

            assertEquals(runId, Routines.cancelMigrationRun(caller.configuration(), label));
            assertEquals(runId, Routines.archiveMigrationRun(caller.configuration(), label));
            assertTrue(Routines.deleteArchivedMigrationRuns1(caller.configuration()) >= 1,
                    "the caller should be able to delete the archived run");
        } finally {
            dsl.execute("DROP FUNCTION IF EXISTS public.caller_mark(bigint)");
        }
    }

    /**
     * The definer-owned population function checks the caller's SELECT privilege
     * before scanning the driving table.
     */
    @Test
    void populateRequiresCallerSelectPrivilege() throws Exception {
        String label = uniqueLabel("populate-privilege");
        try (Connection owner = openOwnerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("REVOKE SELECT ON " + schema(TEST_NO_PK) + "." + name(TEST_NO_PK)
                    + " FROM dml_utils_caller");
        }
        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            assertSqlState("42501", () ->
                    io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                            .populateMigrationBoundaries(caller.configuration(),
                                    schema(TEST_NO_PK), name(TEST_NO_PK), label,
                                    ROLE_TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null));
        } finally {
            try (Connection owner = openOwnerConnection(); Statement statement = owner.createStatement()) {
                statement.execute("GRANT SELECT ON " + schema(TEST_NO_PK) + "." + name(TEST_NO_PK)
                        + " TO dml_utils_caller");
            }
        }
    }

    private long populate(String label) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                label, "SELECT 1", 2, 1, ChunkingStrategy.primary_key, "t", 0, null);
    }

    private long totalCompleted(long runId) {
        return dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED);
    }

}
