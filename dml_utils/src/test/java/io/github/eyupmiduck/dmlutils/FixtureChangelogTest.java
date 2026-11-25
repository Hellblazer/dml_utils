package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Verifies the fixture changelog's re-apply promise: its table/index and grant
 * statements are idempotent, so they can run again against a database whose
 * fixture objects survived a lost Liquibase history. The schema changeset's
 * re-apply is guarded by its Liquibase precondition (and the template build
 * applies that), so only the files without it are replayed here.
 */
class FixtureChangelogTest extends PostgresTestBase {

    private static final List<String> CHANGES = List.of(
            "db/changelog-fixtures/changes/002-create-fixture-tables.sql",
            "db/changelog-fixtures/changes/003-grant-fixtures.sql");

    /**
     * Replaying the fixture table and grant SQL against the populated database is
     * a no-op, so a re-apply after a lost history does not fail with "already
     * exists".
     *
     * @throws Exception if the SQL cannot be executed
     */
    @Test
    void replayingTheFixtureSqlIsANoOp() throws Exception {
        for (String resource : CHANGES) {
            executeAsOwner(TestResources.read(resource));
        }
    }
}
