package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
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
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRunLeaf.MIGRATION_RUN_LEAF;
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

    private static int triggerCount(DSLContext context, String table, String trigger) {
        return context.fetchOne(
                        "SELECT count(*)::int FROM pg_catalog.pg_trigger AS t"
                                + " JOIN pg_catalog.pg_class AS c ON c.oid = t.tgrelid"
                                + " JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = ? AND c.relname = ? AND t.tgname = ?",
                        "dml_utils_data", table, trigger)
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
        assertTrue(hasColumn("dml_utils_data", "migration_run", "partition_wise"),
                "partition_wise should exist");
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
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "leaf_ordinal"),
                "leaf_ordinal should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "updated_at"), "updated_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "started_at"), "started_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "completed_at"), "completed_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "cancelled_at"), "cancelled_at should exist");

        assertTrue(tableExists("dml_utils_data", "migration_run_leaf"),
                "migration_run_leaf should exist");
        for (String column : List.of("run_id", "leaf_ordinal", "leaf_schema_name", "leaf_name",
                "leaf_oid", "leaf_bound", "relation_filepath", "state", "boundary_count",
                "terminal_boundary_id",
                "calculated_at", "error_sqlstate", "error_message", "created_at", "updated_at")) {
            assertTrue(hasColumn("dml_utils_data", "migration_run_leaf", column),
                    column + " should exist");
        }

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
     * Each partition-wise trigger rollback drops exactly its trigger, so a
     * rollback that is replayed after a partial one leaves the other triggers
     * intact.
     */
    @Test
    void triggerRollbacksDropThePartitionWiseTriggers() throws Exception {
        assertRollbackDropsTrigger(
                "db/changelog/changes/triggers-rollback/dml_utils_data/migration_run_leaf_set_updated_at-rollback.sql",
                "migration_run_leaf", "migration_run_leaf_set_updated_at");
        assertRollbackDropsTrigger(
                "db/changelog/changes/triggers-rollback/dml_utils_data/migration_run_leaf_check-rollback.sql",
                "migration_run_leaf", "migration_run_leaf_check");
        assertRollbackDropsTrigger(
                "db/changelog/changes/triggers-rollback/dml_utils_data/migration_boundary_leaf-rollback.sql",
                "migration_boundary", "migration_boundary_leaf");
    }

    private void assertRollbackDropsTrigger(String resource, String table, String trigger)
            throws Exception {
        String script = TestResources.read(resource);
        RuntimeException rollback = new RollbackTransaction();
        try (Connection owner = openOwnerConnection()) {
            DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
            try {
                ownerDsl.transaction(config -> {
                    DSLContext tx = DSL.using(config);
                    // The script is one guarded DO statement, so execute it whole.
                    tx.execute(script);
                    assertEquals(0, triggerCount(tx, table, trigger),
                            "the rollback should drop dml_utils_data." + table + "." + trigger);
                    throw rollback;
                });
                fail("the rollback transaction should have been rolled back");
            } catch (RuntimeException error) {
                if (error != rollback && error.getCause() != rollback) {
                    throw error;
                }
            }
        }
        assertEquals(1, triggerCount(dsl, table, trigger),
                "the transactional rollback should restore dml_utils_data." + table + "." + trigger);
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
     * A leaf manifest and a boundary leaf are accepted only for a partition-wise
     * run, and a partition-wise boundary must identify its leaf.
     */
    @Test
    void enforcesPartitionWiseLeafIdentity() {
        Long partitionWise = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        insertLeaf(partitionWise, 1, "pending", null, 100001L);
        insertBoundary(partitionWise, 0L, 1);

        Long single = insertRun();
        assertSqlState("22023", () -> insertLeaf(single, 1, "pending", null, 100002L));
        assertSqlState("22023", () -> insertBoundary(single, 0L, 1));
        assertSqlState("22023", () -> insertBoundary(partitionWise, 1L, null));
        assertSqlState("23503", () -> insertBoundary(partitionWise, 2L, 99));
    }

    /**
     * A blocks leaf must record its filepath, a key-strategy leaf must not, and
     * the calculation state must agree with its timestamps, errors and count.
     */
    @Test
    void enforcesLeafStrategyAndStateConstraints() {
        Long blocks = insertPartitionWiseRun(ChunkingStrategy.blocks);
        assertSqlState("22023", () -> insertLeaf(blocks, 1, "pending", null, 100001L));
        insertLeaf(blocks, 1, "pending", "base/1/2", 100001L);

        Long key = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        assertSqlState("22023", () -> insertLeaf(key, 1, "pending", "base/1/3", 100002L));
        insertLeaf(key, 1, "pending", null, 100002L);

        assertDomainViolation(() -> dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculated")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(key))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());
        assertDomainViolation(() -> dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "failed")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(key))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());
        assertDomainViolation(() -> dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.BOUNDARY_COUNT, 1L)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(key))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());

        // A calculated non-empty leaf needs a high-water snapshot.
        assertDomainViolation(() -> dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculated")
                .set(MIGRATION_RUN_LEAF.CALCULATED_AT, OffsetDateTime.now())
                .set(MIGRATION_RUN_LEAF.BOUNDARY_COUNT, 2L)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(key))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());

        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculated")
                .set(MIGRATION_RUN_LEAF.CALCULATED_AT, OffsetDateTime.now())
                .set(MIGRATION_RUN_LEAF.BOUNDARY_COUNT, 2L)
                .set(MIGRATION_RUN_LEAF.TERMINAL_BOUNDARY_ID,
                        new MigrationKeyRecord(new Long[]{5L}, null, null, null, null))
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(key))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        // The snapshot is frozen once written.
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.TERMINAL_BOUNDARY_ID,
                        new MigrationKeyRecord(new Long[]{6L}, null, null, null, null))
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(key))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute());
    }

    /**
     * Every column of the leaf identity snapshot is frozen once the manifest is
     * created, and the run's partition_wise flag is immutable.
     */
    @Test
    void enforcesPartitionIdentityImmutability() {
        Long run = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        Long other = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        Long blocks = insertPartitionWiseRun(ChunkingStrategy.blocks);
        insertLeaf(run, 1, "pending", null, 100001L);
        insertLeaf(run, 2, "pending", null, 100002L);
        insertLeaf(blocks, 1, "pending", "base/1/2", 100003L);

        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.RUN_ID, other));
        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.LEAF_ORDINAL, 3));
        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.LEAF_SCHEMA_NAME, "other"));
        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.LEAF_NAME, "renamed"));
        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.LEAF_OID, 100009L));
        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.CREATED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z")));
        assertSqlState("22023", () -> updateLeaf(run, 1,
                MIGRATION_RUN_LEAF.LEAF_BOUND,
                "FOR VALUES FROM ('2026-02-01') TO ('2026-03-01')"));
        // A blocks leaf keeps a non-null filepath, so this reaches the identity
        // snapshot check rather than the strategy rule.
        assertSqlState("22023", () -> updateLeaf(blocks, 1,
                MIGRATION_RUN_LEAF.RELATION_FILEPATH, "base/1/3"));
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.PARTITION_WISE, false)
                .where(MIGRATION_RUN.RUN_ID.eq(run))
                .execute());
    }

    /**
     * A finalized or cancelled run is closed: adding a manifest leaf afterwards
     * is rejected, because it could never be claimed and would stay pending.
     */
    @Test
    void rejectsNewLeavesForFinalizedOrCancelledRuns() {
        Long finalized = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT,
                        OffsetDateTime.parse("2020-01-01T00:00:01Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(finalized))
                .execute();
        assertSqlState("55000", () -> insertLeaf(finalized, 1, "pending", null, 100001L));

        Long cancelled = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_RUN.CANCELLED_AT, OffsetDateTime.parse("2020-01-01T00:00:01Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(cancelled))
                .execute();
        assertSqlState("55000", () -> insertLeaf(cancelled, 1, "pending", null, 100001L));
    }

    /**
     * The ordered-key descriptor identifies the key the boundaries were computed
     * from, so no part of it can change after the run is created.
     */
    @Test
    void enforcesTheOrderedKeyDescriptorImmutability() {
        Long run = insertOrderedKeyRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ORDERED_KEY_INDEX, "other_key")
                .where(MIGRATION_RUN.RUN_ID.eq(run))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ORDERED_KEY_COLUMNS, new String[]{"id"})
                .where(MIGRATION_RUN.RUN_ID.eq(run))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ORDERED_KEY_KINDS, new String[]{"bigint"})
                .where(MIGRATION_RUN.RUN_ID.eq(run))
                .execute());
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_run_leaf}
     * and overwrites a caller-supplied value on UPDATE, so a caller cannot bypass
     * it.
     */
    @Test
    void updatedAtIsMaintainedByTheTriggerOnMigrationRunLeaf() {
        assertTrue(triggerExists("dml_utils_data", "migration_run_leaf",
                        "migration_run_leaf_set_updated_at"),
                "migration_run_leaf_set_updated_at should be attached to dml_utils_data.migration_run_leaf");

        Long run = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        insertLeaf(run, 1, "pending", null, 100001L);

        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.UPDATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(run))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        Boolean recent = dsl.select(
                        MIGRATION_RUN_LEAF.UPDATED_AT.gt(OffsetDateTime.parse("2020-01-01T00:00:00Z")))
                .from(MIGRATION_RUN_LEAF)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(run))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .fetchOne(0, Boolean.class);
        assertEquals(Boolean.TRUE, recent,
                "the trigger should overwrite updated_at with the transaction timestamp");
    }

    /**
     * A boundary cannot move to another leaf or lose its leaf, and a
     * single-relation boundary cannot gain one.
     */
    @Test
    void enforcesBoundaryLeafImmutability() {
        Long run = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        insertLeaf(run, 1, "pending", null, 100001L);
        insertLeaf(run, 2, "pending", null, 100002L);
        insertBoundary(run, 0L, 1);

        assertSqlState("22023", () -> updateBoundary(run, 0L,
                MIGRATION_BOUNDARY.LEAF_ORDINAL, 2));
        assertSqlState("22023", () -> updateBoundary(run, 0L,
                MIGRATION_BOUNDARY.LEAF_ORDINAL, (Integer) null));

        Long single = insertRun();
        insertBoundary(single, 0L, null);
        assertSqlState("22023", () -> updateBoundary(single, 0L,
                MIGRATION_BOUNDARY.LEAF_ORDINAL, 1));
    }

    /**
     * Deleting a run cascades its manifest and boundaries.
     */
    @Test
    void deletingARunCascadesItsLeafManifest() {
        Long run = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        insertLeaf(run, 1, "pending", null, 100001L);
        insertBoundary(run, 0L, 1);

        dsl.deleteFrom(MIGRATION_RUN).where(MIGRATION_RUN.RUN_ID.eq(run)).execute();

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN_LEAF,
                MIGRATION_RUN_LEAF.RUN_ID.eq(run)));
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(run)));
    }

    /**
     * A leaf or boundary for a run that does not exist is rejected with
     * {@code 23503}.
     */
    @Test
    void rejectsLeafRowsForAMissingRun() {
        assertSqlState("23503", () -> insertLeaf(-1L, 1, "pending", null, 100001L));
        assertSqlState("23503", () -> insertBoundary(-1L, 0L, 1));
    }

    /**
     * A valid partition-wise leaf and boundary accept state and processing-time
     * updates; only the identity snapshot is frozen.
     */
    @Test
    void allowsPartitionWiseStateAndTimingUpdates() {
        Long run = insertPartitionWiseRun(ChunkingStrategy.primary_key);
        insertLeaf(run, 1, "pending", null, 100001L);
        insertBoundary(run, 0L, 1);

        dsl.update(MIGRATION_RUN_LEAF)
                .set(MIGRATION_RUN_LEAF.STATE, "calculating")
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(run))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(1))
                .execute();

        OffsetDateTime started = OffsetDateTime.now();
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, started)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, started)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(run))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .execute();
    }

    private <T> int updateLeaf(Long runId, int ordinal, Field<T> field, T value) {
        return dsl.update(MIGRATION_RUN_LEAF)
                .set(field, value)
                .where(MIGRATION_RUN_LEAF.RUN_ID.eq(runId))
                .and(MIGRATION_RUN_LEAF.LEAF_ORDINAL.eq(ordinal))
                .execute();
    }

    private <T> int updateBoundary(Long runId, long boundaryNo, Field<T> field, T value) {
        return dsl.update(MIGRATION_BOUNDARY)
                .set(field, value)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo))
                .execute();
    }

    private Long insertOrderedKeyRun() {
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_INDEX, MIGRATION_RUN.ORDERED_KEY_COLUMNS,
                        MIGRATION_RUN.ORDERED_KEY_KINDS)
                .values(uniqueLabel("ordered-key"), "SELECT 1", 1, ChunkingStrategy.ordered_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source",
                        "migration_tables_source_key", new String[]{"created_at", "id"},
                        new String[]{"timestamptz", "bigint"})
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    private Long insertPartitionWiseRun(ChunkingStrategy chunkBy) {
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.PARTITION_WISE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values(uniqueLabel("partition-wise"), "SELECT 1", 1, chunkBy, true, 1,
                        PUBLIC_SCHEMA, "migration_tables_source")
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    private void insertLeaf(Long runId, int ordinal, String state, String filepath,
                            Long leafOid) {
        dsl.insertInto(MIGRATION_RUN_LEAF)
                .columns(MIGRATION_RUN_LEAF.RUN_ID, MIGRATION_RUN_LEAF.LEAF_ORDINAL,
                        MIGRATION_RUN_LEAF.LEAF_SCHEMA_NAME, MIGRATION_RUN_LEAF.LEAF_NAME,
                        MIGRATION_RUN_LEAF.LEAF_OID, MIGRATION_RUN_LEAF.LEAF_BOUND,
                        MIGRATION_RUN_LEAF.RELATION_FILEPATH, MIGRATION_RUN_LEAF.STATE)
                .values(runId, ordinal, PUBLIC_SCHEMA, "leaf_" + ordinal, leafOid,
                        "FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')",
                        filepath, state)
                .execute();
    }

    private void insertBoundary(Long runId, long boundaryNo, Integer leafOrdinal) {
        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID, MIGRATION_BOUNDARY.LEAF_ORDINAL)
                .values(runId, boundaryNo,
                        new MigrationKeyRecord(new Long[]{1L}, null, null, null, null),
                        leafOrdinal)
                .execute();
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
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, null, null, null, null))
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
                .values(runId, 0L, new MigrationKeyRecord(null, new String[]{"abc"}, null, null, null))
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
                        new OffsetDateTime[]{timestamp, null}, null))
                .execute();

        MigrationKeyRecord key = dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_BOUNDARY.BOUNDARY_ID);

        assertEquals(timestamp.toInstant(), key.getTimestamptzValues()[0].toInstant());
        assertEquals(5L, key.getBigintValues()[1]);
    }

    /**
     * A bytea boundary key is canonical, alone and as the last position of a
     * text key, and a bytea array that disagrees with the arity is rejected.
     */
    @Test
    void acceptsAByteaBoundaryKey() {
        Long runId = insertRun();

        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(
                        null, new String[]{"t", "c", null}, null, null,
                        new byte[][]{null, null, new byte[]{0x00, (byte) 0xff}}))
                .values(runId, 1L, new MigrationKeyRecord(
                        null, null, null, null, new byte[][]{new byte[]{0x00}}))
                .execute();

        MigrationKeyRecord key = dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId).and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L)))
                .fetchOne(MIGRATION_BOUNDARY.BOUNDARY_ID);
        assertArrayEquals(new byte[]{0x00, (byte) 0xff}, key.getByteaValues()[2]);
        assertEquals("c", key.getTextValues()[1]);

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 2L, new MigrationKeyRecord(
                        new Long[]{1L, 2L}, null, null, null, new byte[][]{new byte[]{1}}))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 3L, new MigrationKeyRecord(
                        new Long[]{1L}, null, null, null, new byte[][]{new byte[]{1}}))
                .execute());
    }

    /**
     * A run may record bytea as an ordered-key kind, and an unknown kind is
     * still rejected.
     */
    @Test
    void allowsByteaAsAnOrderedKeyKind() {
        dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_INDEX, MIGRATION_RUN.ORDERED_KEY_COLUMNS,
                        MIGRATION_RUN.ORDERED_KEY_KINDS)
                .values("ordered-key-bytea", "SELECT 1", 1, ChunkingStrategy.ordered_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source",
                        "migration_tables_source_key", new String[]{"tenant_id", "chash"},
                        new String[]{"text", "bytea"})
                .execute();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.ORDERED_KEY_INDEX, MIGRATION_RUN.ORDERED_KEY_COLUMNS,
                        MIGRATION_RUN.ORDERED_KEY_KINDS)
                .values("ordered-key-numeric", "SELECT 1", 1, ChunkingStrategy.ordered_key, 1,
                        PUBLIC_SCHEMA, "migration_tables_source",
                        "migration_tables_source_key", new String[]{"chash"},
                        new String[]{"numeric"})
                .execute());
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
                .values(runId, 0L, new MigrationKeyRecord(null, null, null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{}, null, null, null, null))
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
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L, 2L}, new String[]{"x"}, null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L,
                        new MigrationKeyRecord(new Long[]{1L, null, 3L}, new String[]{null, "x"}, null, null, null))
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
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, new String[]{"abc"}, null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(
                        new Long[]{1L}, null, null,
                        new OffsetDateTime[]{OffsetDateTime.parse("2026-01-01T00:00:00Z")}, null))
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
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L, 2L, 3L, 4L}, null, null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, new String[]{null}, null, null, null))
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

        // The milestone itself is frozen, so the total_chunks freeze cannot be
        // bypassed by clearing it first.
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT, (OffsetDateTime) null)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.BOUNDARIES_CALCULATED_AT,
                        OffsetDateTime.parse("2020-01-01T00:00:02Z"))
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
     * updating any of them is rejected. {@code completed_at} may be written once,
     * but a later rewrite (including clearing it) is rejected.
     */
    @Test
    void rejectsUpdatesToImmutableBoundaryColumns() {
        long runId = insertRunWithBoundary();
        long otherRunId = insertRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_ID, new MigrationKeyRecord(new Long[]{999L}, null, null, null, null))
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

        // The first completed_at write is allowed, in the order the time-order
        // check requires; it is then frozen.
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.STARTED_AT, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.parse("2020-01-02T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();

        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.parse("2020-01-03T00:00:00Z"))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, (OffsetDateTime) null)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
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
