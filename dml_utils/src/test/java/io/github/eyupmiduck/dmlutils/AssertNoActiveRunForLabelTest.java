package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;

/**
 * Verifies {@code dml_utils_lib.assert_no_active_run_for_label}: it returns
 * normally when the label has no active run, raises unique_violation
 * ({@code 23505}) when it does, and ignores archived runs.
 */
class AssertNoActiveRunForLabelTest extends PostgresTestBase {

    /**
     * A label with no run at all is accepted.
     */
    @Test
    void acceptsALabelWithNoActiveRun() {
        Routines.assertNoActiveRunForLabel(dsl.configuration(), uniqueLabel("no-active"));
    }

    /**
     * A label whose only run is active raises {@code 23505}.
     */
    @Test
    void rejectsALabelWithAnActiveRun() {
        String label = uniqueLabel("active");
        insertRun(label, schema(), name());

        assertSqlState("23505", () -> Routines.assertNoActiveRunForLabel(dsl.configuration(), label));
    }

    /**
     * A label whose only run is archived is accepted: the uniqueness rule is on
     * active (not archived) runs.
     */
    @Test
    void acceptsALabelWhoseOnlyRunIsArchived() {
        String label = uniqueLabel("archived");
        long runId = insertRun(label, schema(), name());
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ARCHIVED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        Routines.assertNoActiveRunForLabel(dsl.configuration(), label);
    }

}
