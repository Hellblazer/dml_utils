package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@code dml_utils_lib.claim_migration_boundary}: a pending boundary is
 * locked and its claim timestamp returned without writing the boundary, the lock
 * lasts until the transaction ends, and a missing, completed or cancelled
 * boundary raises {@code P0C01}.
 */
class ClaimMigrationBoundaryTest extends PostgresTestBase {

    /**
     * Empties the fixture and the migration metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * Claiming a pending boundary returns a timestamp and leaves the row pending;
     * once the implicit transaction ends the lock is released and the same
     * boundary can be claimed again.
     */
    @Test
    void claimsAPendingBoundaryWithoutWritingIt() {
        seedBigint(1, 2, 3);
        long runId = populate(1);

        assertNotNull(claim(runId, 0));
        MigrationBoundaryRecord boundary = boundary(runId, 0);
        assertNull(boundary.getStartedAt(), "a claim does not start the chunk");
        assertNull(boundary.getCompletedAt(), "a claim does not complete the chunk");
        assertNull(boundary.getCancelledAt(), "a claim does not cancel the chunk");

        assertNotNull(claim(runId, 0), "the lock is transaction-scoped, so a new transaction can claim it");
    }

    /**
     * A missing run or boundary, a completed boundary and a cancelled boundary
     * all raise the claim protocol's {@code P0C01}.
     */
    @Test
    void rejectsMissingCompletedAndCancelledBoundaries() {
        seedBigint(1, 2, 3);
        long runId = populate(1);

        assertSqlState("P0C01", () -> claim(-1, 0));
        assertSqlState("P0C01", () -> claim(runId, 999));

        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.now())
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .execute();
        assertSqlState("P0C01", () -> claim(runId, 0));

        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.CANCELLED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(1L))
                .execute();
        assertSqlState("P0C01", () -> claim(runId, 1));
    }

    /**
     * The row lock is retained after the function returns and until the caller's
     * transaction ends: a second session times out while the first holds it, then
     * succeeds after the first rolls back.
     */
    @Test
    void holdsTheBoundaryLockUntilTheTransactionEnds() throws Exception {
        seedBigint(1, 2, 3);
        long runId = populate(1);

        try (Connection holder = openTestConnection();
             Connection waiter = openTestConnection()) {
            holder.setAutoCommit(false);
            waiter.setAutoCommit(false);
            DSLContext holderDsl = DSL.using(holder, SQLDialect.POSTGRES);
            DSLContext waiterDsl = DSL.using(waiter, SQLDialect.POSTGRES);

            assertNotNull(Routines.claimMigrationBoundary(
                    holderDsl.configuration(), runId, 0L), "the holder claims first");

            setTimeout(waiter, "250ms");
            assertSqlState("57014", () -> Routines.claimMigrationBoundary(
                    waiterDsl.configuration(), runId, 0L));
            waiter.rollback();

            holder.rollback();
            setTimeout(waiter, "5s");
            assertNotNull(Routines.claimMigrationBoundary(
                    waiterDsl.configuration(), runId, 0L),
                    "the waiter can claim after the holder releases the lock");
            waiter.rollback();
        }
    }

    private long populate(int chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(),
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                uniqueLabel("claim-boundary"), "SELECT 1", chunkSize, 1,
                ChunkingStrategy.primary_key, "t", 0, null, false);
    }

    private OffsetDateTime claim(long runId, long boundaryNo) {
        return Routines.claimMigrationBoundary(dsl.configuration(), runId, boundaryNo);
    }

    private MigrationBoundaryRecord boundary(long runId, long boundaryNo) {
        return dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo))
                .fetchOne();
    }

    private void setTimeout(Connection connection, String timeout) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '" + timeout + "'");
        }
    }
}
