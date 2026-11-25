package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.archive_migration_run}: it cancels the active run
 * before archiving it, stamps the archive after the cancellation, archives the
 * run that is active for the label (not a later one), and returns NULL when
 * there is nothing active to archive.
 */
class ArchiveMigrationRunTest extends PostgresTestBase {

    /**
     * Empties the migration metadata and the bigint fixture between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndBigint();
    }

    /**
     * An unfinished run is cancelled and then archived, and the archive
     * timestamp is not earlier than the cancellation it performed.
     */
    @Test
    void archivesTheActiveRunAfterCancellingIt() {
        String label = uniqueLabel("archive");
        long runId = populateSingleRelation(label, "SELECT 1", 1);

        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));

        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getArchivedAt(), "the run is archived");
        assertNotNull(run.getCancelledAt(), "an unfinished run is cancelled first");
        assertFalse(run.getArchivedAt().isBefore(run.getCancelledAt()),
                "archived_at must not precede cancelled_at");
    }

    /**
     * A run that was already cancelled is archived without moving its
     * cancellation timestamp, so the inner cancel is a no-op.
     */
    @Test
    void archivesAnAlreadyCancelledRunWithoutMovingTheTimestamp() {
        String label = uniqueLabel("archive-cancelled");
        long runId = populateSingleRelation(label, "SELECT 1", 1);
        assertEquals(runId, Routines.cancelMigrationRun(dsl.configuration(), label));
        OffsetDateTime cancelled = migrationRun(runId).getCancelledAt();
        assertNotNull(cancelled);

        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));

        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getArchivedAt());
        assertEquals(cancelled, run.getCancelledAt(),
                "the existing cancellation timestamp is kept");
        assertFalse(run.getArchivedAt().isBefore(cancelled));
    }

    /**
     * Archiving a label, then creating a new run with the same label, archives
     * each run separately: the stale archive does not touch the newer run.
     */
    @Test
    void archivesOnlyTheRunActiveForTheLabel() {
        String label = uniqueLabel("archive-recreate");
        long first = populateSingleRelation(label, "SELECT 1", 1);
        assertEquals(first, Routines.archiveMigrationRun(dsl.configuration(), label));
        OffsetDateTime firstArchived = migrationRun(first).getArchivedAt();
        assertNotNull(firstArchived);

        long second = populateSingleRelation(label, "SELECT 1", 1);
        assertNotEquals(first, second, "archiving the label allows a new run");
        assertEquals(second, Routines.archiveMigrationRun(dsl.configuration(), label));

        assertEquals(firstArchived, migrationRun(first).getArchivedAt(),
                "the first run's archive is untouched");
        assertNotNull(migrationRun(second).getArchivedAt(),
                "the second run is archived");
    }

    /**
     * While this archive call waits on the run row lock, another session
     * archives the old run and re-creates the label. The blocked call must
     * return NULL and leave the fresh run active: it must not fall through to a
     * label-based update that would cancel-and-archive the new run.
     */
    @Test
    void doesNotArchiveALabelRecreatedWhileItWaitsOnTheRunLock() throws Exception {
        String label = uniqueLabel("archive-race");
        long original = populateSingleRelation(label, "SELECT 1", 1);

        try (Connection holder = openOwnerConnection()) {
            holder.setAutoCommit(false);
            DSLContext holderDsl = DSL.using(holder, SQLDialect.POSTGRES);
            try {
                // Hold the original run's row lock so the concurrent archive
                // blocks before it can cancel or update anything.
                holderDsl.selectOne()
                        .from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.RUN_ID.eq(original))
                        .forUpdate()
                        .fetchOne();

                CompletableFuture<Long> blocked = CompletableFuture.supplyAsync(() -> {
                    try (Connection archive = openOwnerConnection()) {
                        return Routines.archiveMigrationRun(
                                DSL.using(archive, SQLDialect.POSTGRES).configuration(), label);
                    } catch (SQLException error) {
                        throw new CompletionException(error);
                    }
                });
                awaitArchiveLockWait();

                // The holder archives the old run and creates a fresh one with
                // the same label, then commits while the other call waits.
                assertEquals(original, Routines.archiveMigrationRun(
                        holderDsl.configuration(), label));
                long recreated = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                        .populateMigrationBoundaries(holderDsl.configuration(),
                                schema(TEST_BIGINT), name(TEST_BIGINT), label, "SELECT 1",
                                1, 1, ChunkingStrategy.primary_key, "t", 0, null, false,
                                3, 1000);
                holder.commit();

                assertNull(blocked.get(30, TimeUnit.SECONDS),
                        "the blocked archive must not archive the recreated run");
                assertNull(migrationRun(recreated).getArchivedAt(),
                        "the recreated run stays unarchived");
                assertNull(migrationRun(recreated).getCancelledAt(),
                        "the recreated run stays uncancelled");
            } finally {
                holder.rollback();
            }
        }
    }

    /**
     * Waits until a backend is blocked inside archive_migration_run.
     *
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitArchiveLockWait() throws Exception {
        String database = currentDatabase();
        try (Connection admin = openAdminConnection(database)) {
            DSLContext adminDsl = DSL.using(admin, SQLDialect.POSTGRES);
            for (int attempt = 0; attempt < 400; attempt++) {
                Boolean waiting = adminDsl.fetchOne("""
                                SELECT EXISTS (SELECT 1
                                               FROM pg_catalog.pg_stat_activity
                                               WHERE datname = pg_catalog.current_database()
                                                 AND wait_event_type = 'Lock'
                                                 AND query LIKE '%archive_migration_run%')
                                """)
                        .get(0, Boolean.class);
                if (Boolean.TRUE.equals(waiting)) {
                    return;
                }
                Thread.sleep(25);
            }
        }
        fail("the archive call did not block on the run lock");
    }

    /**
     * A missing active run returns NULL, and a second archive of the same label
     * has nothing left to archive.
     */
    @Test
    void returnsNullWhenThereIsNoActiveRun() {
        assertNull(Routines.archiveMigrationRun(
                dsl.configuration(), uniqueLabel("archive-none")));

        String label = uniqueLabel("archive-twice");
        long runId = populateSingleRelation(label, "SELECT 1", 1);
        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));
        assertNull(Routines.archiveMigrationRun(dsl.configuration(), label),
                "the archived run is not active any more");
    }
}
