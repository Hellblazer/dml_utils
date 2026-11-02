package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Liquibase changelog loads the migration tables with the
 * expected columns and that the shared {@code set_updated_at()} trigger keeps
 * their {@code updated_at} current.
 */
class MigrationTablesTest extends PostgresTestBase {

    private static int routineCount(DSLContext context, String schema, String name) {
        return context.fetchOne(
                        "SELECT count(*)::int FROM pg_catalog.pg_proc AS p"
                                + " JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace"
                                + " WHERE n.nspname = ? AND p.proname = ?",
                        schema, name)
                .get(0, Integer.class);
    }

    private static void executeStatements(DSLContext context, String sql) {
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                context.execute(statement);
            }
        }
    }

    /**
     * The migration tables exist in the {@code dml_utils} schema with the
     * expected columns.
     */
    @Test
    void migrationTablesAreLoaded() {
        assertTrue(tableExists("dml_utils_data", "migration_run"), "migration_run should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "updated_at"), "updated_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "started_at"), "started_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "boundaries_calculated_at"),
                "boundaries_calculated_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "completed_at"), "completed_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "cancelled_at"), "cancelled_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "sleep_ms"), "sleep_ms should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "driving_table_schema_name"),
                "driving_table_schema_name should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "driving_table_name"),
                "driving_table_name should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "chunk_by"), "chunk_by should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "driving_table_relation_filepath"),
                "driving_table_relation_filepath should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "total_chunks"),
                "total_chunks should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "total_chunks_completed"),
                "total_chunks_completed should exist");

        assertTrue(tableExists("dml_utils_data", "migration_boundary"), "migration_boundary should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "boundary_no"), "boundary_no should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "boundary_id"), "boundary_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "updated_at"), "updated_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "started_at"), "started_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "completed_at"), "completed_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "cancelled_at"), "cancelled_at should exist");

        assertTrue(tableExists("dml_utils_data", "migration_error"), "migration_error should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "error_id"), "error_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "boundary_no"), "boundary_no should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "sqlstate"), "sqlstate should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "message"), "message should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "updated_at"), "updated_at should exist");
    }

    /**
     * The per-chunk sleep defaults to zero and rejects a negative value.
     */
    @Test
    void sleepMsDefaultsToZeroAndRejectsNegativeValues() {
        Long runId = insertRun();

        Integer sleepMs = dsl.select(MIGRATION_RUN.SLEEP_MS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.SLEEP_MS);
        assertEquals(0, sleepMs, "a run without an explicit value has no delay");

        assertDomainViolation(() -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.SLEEP_MS, -1)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
    }

    /**
     * The rollback scripts for the routines whose signatures changed drop the
     * current definitions (not a stale older signature).
     */
    @Test
    void routineRollbacksDropTheCurrentSignatures() throws Exception {
        assertRollbackDropsFunction(
                "db/changelog/changes/functions-rollback/dml_utils/run_migration_chunks-rollback.sql",
                "dml_utils", "run_migration_chunks");
        assertRollbackDropsFunction(
                "db/changelog/changes/functions-rollback/dml_utils/run_function_over_table-rollback.sql",
                "dml_utils", "run_function_over_table");
        assertRollbackDropsFunction(
                "db/changelog/changes/functions-rollback/dml_utils_lib/resolve_migration_run-rollback.sql",
                "dml_utils_lib", "resolve_migration_run");
        assertRollbackDropsFunction(
                "db/changelog/changes/functions-rollback/dml_utils_lib/populate_migration_boundaries-rollback.sql",
                "dml_utils_lib", "populate_migration_boundaries");
        assertRollbackDropsFunction(
                "db/changelog/changes/functions-rollback/dml_utils/set_migration_run_sleep_ms-rollback.sql",
                "dml_utils", "set_migration_run_sleep_ms");
    }

    private void assertRollbackDropsFunction(String resource, String schema, String name)
            throws Exception {
        String script = TestResources.read(resource);
        RuntimeException rollback = new RollbackTransaction();
        try (Connection owner = openOwnerConnection()) {
            DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
            try {
                ownerDsl.transaction(config -> {
                    DSLContext tx = DSL.using(config);
                    executeStatements(tx, script);
                    assertEquals(0, routineCount(tx, schema, name),
                            "the rollback should drop " + schema + "." + name);
                    throw rollback;
                });
                fail("the rollback transaction should have been rolled back");
            } catch (RuntimeException error) {
                if (error != rollback && error.getCause() != rollback) {
                    throw error;
                }
            }
        }
        assertEquals(1, routineCount(dsl, schema, name),
                "the transactional rollback should restore " + schema + "." + name);
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_run} and
     * overwrites a caller-supplied value on UPDATE, so a caller cannot bypass
     * it.
     */
    @Test
    void updatedAtIsMaintainedByTheTriggerOnMigrationRun() {
        assertTrue(triggerExists("dml_utils_data", "migration_run", "migration_run_set_updated_at"),
                "migration_run_set_updated_at should be attached to dml_utils_data.migration_run");

        Long runId = insertRun();

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.UPDATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        Boolean recent = dsl.select(MIGRATION_RUN.UPDATED_AT.gt(OffsetDateTime.parse("2020-01-01T00:00:00Z")))
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(0, Boolean.class);
        assertEquals(Boolean.TRUE, recent, "the trigger should overwrite updated_at with the transaction timestamp");
    }

    /**
     * A cancellation must not precede the run start, and a cancellation that is
     * already set cannot be rewritten.
     */
    @Test
    void enforcesTheRunCancellationTimeline() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.parse("2020-01-03T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.parse("2020-01-04T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        // Writing the identical timestamp is a trigger-safe no-op: the
        // coordinator's COALESCE(cancelled_at, clock_timestamp()) relies on it.
        OffsetDateTime cancelledAt = dsl.select(MIGRATION_RUN.CANCELLED_AT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.CANCELLED_AT);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, cancelledAt)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        // Clearing a set cancellation back to NULL is also rejected.
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CANCELLED_AT, (OffsetDateTime) null)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
    }

    /**
     * A run cannot be both completed and cancelled.
     */
    @Test
    void rejectsACompletedAndCancelledRun() {
        Long runId = insertRun();

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        assertDomainViolation(() -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.parse("2020-01-03T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
    }

    /**
     * A boundary's cancellation cannot be rewritten once set.
     */
    @Test
    void rejectsRewritingABoundaryCancellation() {
        long runId = insertRunWithBoundary();

        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.CANCELLED_AT, OffsetDateTime.parse("2030-01-01T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();

        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.CANCELLED_AT, OffsetDateTime.parse("2030-01-02T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());

        // Clearing a set cancellation back to NULL is also rejected.
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.CANCELLED_AT, (OffsetDateTime) null)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
    }

    /**
     * Inserts a run with a unique label, returning its {@code run_id}.
     */
    private Long insertRun() {
        return insertRun("migration-tables-test-" + UUID.randomUUID(),
                PUBLIC_SCHEMA, "migration_tables_source");
    }

    /**
     * Inserts a run and one boundary row, returning the {@code run_id}.
     */
    private long insertRunWithBoundary() {
        Long runId = insertRun();
        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, null, null, null))
                .execute();
        return runId;
    }

    /**
     * The boundary key check constraint accepts a key with at least one
     * populated array.
     */
    @Test
    void acceptsASingleAttributeBoundaryKey() {
        Long runId = insertRun();

        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(null, new String[]{"abc"}, null, null))
                .execute();

        assertEquals("abc", dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .getTextValues()[0]);
    }

    /**
     * A timestamptz boundary key is canonical, including in a mixed composite
     * key where each position uses a different array.
     */
    @Test
    void acceptsATimestamptzBoundaryKey() {
        Long runId = insertRun();
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z");

        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(
                        new Long[]{null, 5L}, null, null,
                        new OffsetDateTime[]{timestamp, null}))
                .execute();

        MigrationKeyRecord key = dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_BOUNDARY.BOUNDARY_ID);

        assertEquals(timestamp.toInstant(), key.getTimestamptzValues()[0].toInstant());
        assertEquals(5L, key.getBigintValues()[1]);
    }

    /**
     * A boundary key with no populated array violates the check constraint, and
     * so does an empty array (it carries no key value).
     */
    @Test
    void rejectsBoundaryKeysWithNoPopulatedOrEmptyArray() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(null, null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{}, null, null, null))
                .execute());
    }

    /**
     * The boundary key check rejects present arrays of differing lengths, even
     * when no single index holds two values (the second case below passes the
     * per-index rule but not the shared-arity rule).
     */
    @Test
    void rejectsBoundaryKeysWithMismatchedArrayLengths() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L, 2L}, new String[]{"x"}, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L,
                        new MigrationKeyRecord(new Long[]{1L, null, 3L}, new String[]{null, "x"}, null, null))
                .execute());
    }

    /**
     * The boundary key check rejects an equal-length key with two values at the
     * same index: exactly one array may hold a value per position.
     */
    @Test
    void rejectsBoundaryKeysWithTwoValuesAtOneIndex() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, new String[]{"abc"}, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(
                        new Long[]{1L}, null, null,
                        new OffsetDateTime[]{OffsetDateTime.parse("2026-01-01T00:00:00Z")}))
                .execute());
    }

    /**
     * The boundary key check rejects a key of more than three columns (the
     * arity cap) and a present-but-all-NULL array, which only the non-emptiness
     * guard rejects.
     */
    @Test
    void rejectsBoundaryKeysOverTheArityCapOrWithAnAllNullArray() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L, 2L, 3L, 4L}, null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, new String[]{null}, null, null))
                .execute());
    }

    /**
     * An ordered-key run records its index and key descriptor, and the
     * strategy/descriptor combinations are enforced.
     */
    @Test
    void enforcesTheOrderedKeyDescriptorCombination() {
        dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_INDEX, MIGRATION_RUN.ORDERED_KEY_COLUMNS,
                        MIGRATION_RUN.ORDERED_KEY_KINDS)
                .values("ordered-key-run", "SELECT 1", 1, ChunkingStrategy.ordered_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source",
                        "migration_tables_source_key", new String[]{"created_at", "id"},
                        new String[]{"timestamptz", "bigint"})
                .execute();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("ordered-key-missing-descriptor", "SELECT 1", 1, ChunkingStrategy.ordered_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source")
                .execute());

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_INDEX, MIGRATION_RUN.ORDERED_KEY_COLUMNS,
                        MIGRATION_RUN.ORDERED_KEY_KINDS)
                .values("primary-key-with-descriptor", "SELECT 1", 1, ChunkingStrategy.primary_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source",
                        "migration_tables_source_key", new String[]{"id"}, new String[]{"bigint"})
                .execute());

        // A partly populated descriptor is rejected in both directions: a
        // non-ordered run may not carry a stray index, and an ordered run must
        // supply all three parts.
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_INDEX)
                .values("primary-key-with-partial-descriptor", "SELECT 1", 1,
                        ChunkingStrategy.primary_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source", "migration_tables_source_key")
                .execute());

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_COLUMNS, MIGRATION_RUN.ORDERED_KEY_KINDS)
                .values("ordered-key-without-index", "SELECT 1", 1, ChunkingStrategy.ordered_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source",
                        new String[]{"id"}, new String[]{"bigint"})
                .execute());
    }

    /**
     * A non-positive {@code chunk_size} is rejected by the check constraint.
     */
    @Test
    void rejectsNonPositiveChunkSize() {
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("chunk-size-check", "SELECT 1", 0, 1, PUBLIC_SCHEMA, "migration_tables_source")
                .execute());
    }

    /**
     * The progress counters are non-negative and a run cannot report more
     * completed chunks than it has.
     */
    @Test
    void rejectsInvalidProgressCounters() {
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.TOTAL_CHUNKS)
                .values("negative-total-chunks-" + UUID.randomUUID(), "SELECT 1", 1, 1,
                        PUBLIC_SCHEMA, "migration_tables_source", -1L)
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED)
                .values("negative-completed-" + UUID.randomUUID(), "SELECT 1", 1, 1,
                        PUBLIC_SCHEMA, "migration_tables_source", -1L)
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME, MIGRATION_RUN.TOTAL_CHUNKS,
                        MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED)
                .values("too-many-completed-" + UUID.randomUUID(), "SELECT 1", 1, 1,
                        PUBLIC_SCHEMA, "migration_tables_source", 2L, 3L)
                .execute());
    }

    /**
     * {@code total_chunks} may be written with {@code boundaries_calculated_at}
     * but is immutable afterwards; {@code total_chunks_completed} stays mutable.
     */
    @Test
    void freezesTotalChunksAfterBoundariesAreCalculated() {
        Long runId = insertRun();

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT, OffsetDateTime.parse("2020-01-01T00:00:01Z"))
                .set(MIGRATION_RUN.TOTAL_CHUNKS, 5L)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS, 3L)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        // total_chunks_completed remains mutable, up to total_chunks.
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED, 2L)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * {@code label}, {@code chunk_size} and the driving table schema/name are
     * immutable: updating any of them is rejected, while updating a mutable
     * column (for example {@code completed_at}) is allowed.
     */
    @Test
    void rejectsUpdatesToImmutableRunColumns() {
        Long runId = insertRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.LABEL, "changed")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CHUNK_SIZE, 99)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, "other")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.DRIVING_TABLE_NAME, "other")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CHUNK_BY, ChunkingStrategy.blocks)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH, "base/1/2")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        // The mutable started_at/completed_at columns can still be updated, in
        // the order the time-order check requires.
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * {@code run_id}, {@code boundary_no} and {@code boundary_id} are immutable:
     * updating any of them is rejected, while updating the mutable
     * {@code completed_at} is allowed.
     */
    @Test
    void rejectsUpdatesToImmutableBoundaryColumns() {
        long runId = insertRunWithBoundary();
        long otherRunId = insertRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_ID, new MigrationKeyRecord(new Long[]{999L}, null, null, null))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_NO, 999L)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.RUN_ID, otherRunId)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());

        // The mutable started_at/completed_at columns can still be updated, in
        // the order the time-order check requires.
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * The time-order check constraints reject a completion with no start, and a
     * completion earlier than the start, on both {@code migration_run} and
     * {@code migration_boundary}.
     */
    @Test
    void rejectsCompletionWithoutStartOrBeforeStart() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertDomainViolation(() -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        long boundaryRunId = insertRunWithBoundary();
        assertDomainViolation(() -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(boundaryRunId))
                .execute());
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(boundaryRunId))
                .execute();
        assertDomainViolation(() -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(boundaryRunId))
                .execute());
    }

    /**
     * The time-order check rejects a {@code boundaries_calculated_at} earlier
     * than {@code started_at}.
     */
    @Test
    void rejectsBoundariesCalculatedBeforeStart() {
        Long runId = insertRun();

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        assertDomainViolation(() -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT,
                        OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
    }

    /**
     * The blocks<->filepath check rejects a block run without a filepath and a
     * primary-key run with one.
     */
    @Test
    void rejectsAMismatchedChunkByFilepath() {
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("block-no-filepath-" + UUID.randomUUID(), "SELECT 1", 1,
                        ChunkingStrategy.blocks, 1, PUBLIC_SCHEMA, "source")
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH)
                .values("pk-with-filepath-" + UUID.randomUUID(), "SELECT 1", 1,
                        ChunkingStrategy.primary_key, 1, PUBLIC_SCHEMA, "source", "base/1/2")
                .execute());
    }

    /**
     * The shared timestamp columns are {@code timestamptz NOT NULL}, and the
     * primary and foreign keys are the composite ones the routines rely on.
     */
    @Test
    void migrationTableMetadataIsAsExpected() {
        for (String table : List.of("migration_run", "migration_boundary", "migration_error")) {
            for (String column : List.of("created_at", "updated_at")) {
                Record row = dsl.fetchOne(
                        "SELECT data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = 'dml_utils_data'"
                                + " AND table_name = ? AND column_name = ?",
                        table, column);
                assertNotNull(row, table + "." + column + " should exist");
                assertEquals("timestamp with time zone", row.get("data_type", String.class),
                        table + "." + column + " should be timestamptz");
                assertEquals("NO", row.get("is_nullable", String.class),
                        table + "." + column + " should be NOT NULL");
            }
        }

        assertEquals(List.of("run_id"), constraintColumns("migration_run", "p"),
                "migration_run's primary key");
        assertEquals(List.of("run_id", "boundary_no"),
                constraintColumns("migration_boundary", "p"),
                "migration_boundary's composite primary key");
        assertEquals(List.of("run_id", "boundary_no"),
                constraintColumns("migration_error", "f"),
                "migration_error's composite foreign key");
    }

    private List<String> constraintColumns(String table, String constraintType) {
        return dsl.fetch(
                "SELECT a.attname FROM pg_constraint con"
                        + " JOIN pg_class c ON c.oid = con.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)"
                        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum"
                        + " WHERE con.contype = ? AND n.nspname = 'dml_utils_data'"
                        + " AND c.relname = ? ORDER BY k.ord",
                constraintType, table).getValues(0, String.class);
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_boundary}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationBoundary() {
        assertTrue(triggerExists("dml_utils_data", "migration_boundary", "migration_boundary_set_updated_at"),
                "migration_boundary_set_updated_at should be attached to dml_utils_data.migration_boundary");
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_error}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationError() {
        assertTrue(triggerExists("dml_utils_data", "migration_error", "migration_error_set_updated_at"),
                "migration_error_set_updated_at should be attached to dml_utils_data.migration_error");
    }

    /**
     * The {@code updated_at} trigger overwrites a caller-supplied timestamp on
     * UPDATE for both {@code migration_boundary} and {@code migration_error}, not
     * merely being attached to the table.
     */
    @Test
    void updatedAtIsMaintainedByTheTriggerOnBoundaryAndError() {
        long runId = insertRunWithBoundary();
        dsl.insertInto(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID, MIGRATION_ERROR.BOUNDARY_NO,
                        MIGRATION_ERROR.SQLSTATE, MIGRATION_ERROR.MESSAGE)
                .values(runId, 0L, "22012", "boom")
                .execute();

        OffsetDateTime old = OffsetDateTime.parse("2000-01-01T00:00:00Z");
        OffsetDateTime recent = OffsetDateTime.parse("2020-01-01T00:00:00Z");

        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.UPDATED_AT, old)
                .set(MIGRATION_BOUNDARY.STARTED_AT, old)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();
        dsl.update(MIGRATION_ERROR)
                .set(MIGRATION_ERROR.UPDATED_AT, old)
                .set(MIGRATION_ERROR.MESSAGE, "changed")
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .execute();

        assertEquals(Boolean.TRUE, dsl.select(MIGRATION_BOUNDARY.UPDATED_AT.gt(recent))
                .from(MIGRATION_BOUNDARY).where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .fetchOne(0, Boolean.class), "the boundary trigger should overwrite updated_at");
        assertEquals(Boolean.TRUE, dsl.select(MIGRATION_ERROR.UPDATED_AT.gt(recent))
                .from(MIGRATION_ERROR).where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne(0, Boolean.class), "the error trigger should overwrite updated_at");
    }

    private static final class RollbackTransaction extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
