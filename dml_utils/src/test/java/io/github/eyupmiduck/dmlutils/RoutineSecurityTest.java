package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile;
import io.github.eyupmiduck.changelogvalidator.testing.RoutineSecurityContract;

import java.util.Set;

/**
 * Verifies the routine security conventions, inheriting the shared
 * {@link RoutineSecurityContract}: only deliberate {@code SECURITY DEFINER}
 * routines are allowed, catalog-inspecting routines pin {@code search_path} to
 * {@code pg_catalog}, and no routine uses a caller-controlled path.
 */
class RoutineSecurityTest extends RoutineSecurityContract {

    /**
     * The owner-owned metadata writers are {@code SECURITY DEFINER}: the caller
     * can read migration metadata but writes go through these helpers, so a
     * caller cannot forge a run or boundary directly.
     *
     * @return the allowed definers
     */
    @Override
    protected Set<String> allowedSecurityDefiners() {
        return Set.of(
                "dml_utils.archive_migration_run",
                "dml_utils.cancel_migration_run",
                "dml_utils.delete_archived_migration_runs",
                "dml_utils.pause_migration_run",
                "dml_utils.resume_migration_run",
                "dml_utils.set_migration_run_function",
                "dml_utils.set_migration_run_retries",
                "dml_utils.set_migration_run_sleep_ms",
                "dml_utils.set_migration_run_sql_text",
                "dml_utils.set_migration_run_threads",
                "dml_utils_lib.append_terminal_boundary",
                "dml_utils_lib.calculate_migration_leaf",
                "dml_utils_lib.claim_migration_boundary",
                "dml_utils_lib.claim_next_migration_leaf",
                "dml_utils_lib.complete_migration_boundary",
                "dml_utils_lib.complete_migration_run",
                "dml_utils_lib.finalize_migration_boundaries",
                "dml_utils_lib.mark_migration_run_cancelled",
                "dml_utils_lib.populate_migration_boundaries",
                "dml_utils_lib.record_migration_error",
                "dml_utils_lib.record_migration_leaf_error",
                "dml_utils_lib.snapshot_partition_leaf_manifest");
    }

    /**
     * The catalog-inspecting library routines that must pin
     * {@code search_path=pg_catalog}, so a caller's path cannot change which
     * object an unqualified reference resolves to.
     *
     * @return the catalog routines
     */
    @Override
    protected Set<String> catalogRoutines() {
        return Set.of(
                "dml_utils_lib.assert_partitioned_table",
                "dml_utils_lib.ordered_key_attributes_for_index");
    }

    /**
     * The dml_utils profile, so this contract uses the project's roles, image,
     * changelog and schemas rather than the library defaults.
     *
     * @return the dml_utils profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PostgresTestBase.PROFILE;
    }
}
