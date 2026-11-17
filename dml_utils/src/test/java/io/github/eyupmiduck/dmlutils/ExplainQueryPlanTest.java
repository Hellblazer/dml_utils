package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.JSON;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@code dml_utils_lib.explain_query_plan}: it returns the
 * {@code EXPLAIN (FORMAT JSON)} plan of the supplied statement without executing
 * it, and rejects a NULL or empty statement.
 */
class ExplainQueryPlanTest extends PostgresTestBase {

    /**
     * Empties the fixture table so a row seeded by one test cannot collide with
     * (or mask) another test's write.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetFixture() {
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A SELECT is planned and the json carries the top-level plan node.
     */
    @Test
    void explainsASelect() {
        JSON plan = Routines.explainQueryPlan(dsl.configuration(), "SELECT 1");

        assertEquals("Result", planNodeType(plan));
    }

    /**
     * An UPDATE is planned as a ModifyTable and is not executed: an existing row
     * keeps its value. The row is seeded first, so a wrongly executed UPDATE
     * would be observable (an empty table would hide it).
     */
    @Test
    void explainsAnUpdateWithoutExecutingIt() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID, TEST_BIGINT.PAYLOAD)
                .values(1L, "keep").execute();

        JSON plan = Routines.explainQueryPlan(dsl.configuration(),
                "UPDATE dml_utils_fixtures.test_bigint SET payload = 'x'");

        assertEquals("ModifyTable", planNodeType(plan));
        assertEquals("keep", dsl.select(TEST_BIGINT.PAYLOAD)
                        .from(TEST_BIGINT)
                        .where(TEST_BIGINT.ID.eq(1L))
                        .fetchOne(TEST_BIGINT.PAYLOAD),
                "EXPLAIN must not run the update");
        assertEquals(1, dsl.fetchCount(TEST_BIGINT), "no row is added or removed");
    }

    /**
     * An INSERT is planned as a ModifyTable and is not executed: the seeded row is
     * still the only one.
     */
    @Test
    void explainsAnInsertWithoutExecutingIt() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID, TEST_BIGINT.PAYLOAD)
                .values(1L, "keep").execute();

        JSON plan = Routines.explainQueryPlan(dsl.configuration(),
                "INSERT INTO dml_utils_fixtures.test_bigint (id, payload) VALUES (2, 'x')");

        assertEquals("ModifyTable", planNodeType(plan));
        assertEquals(1, dsl.fetchCount(TEST_BIGINT), "EXPLAIN must not run the insert");
        assertEquals(0, dsl.fetchCount(TEST_BIGINT, TEST_BIGINT.ID.eq(2L)),
                "the inserted row does not exist");
    }

    /**
     * A NULL, empty or blank statement is rejected with {@code 22023}.
     */
    @Test
    void rejectsNullOrEmptyStatements() {
        assertDomainViolation(() -> Routines.explainQueryPlan(dsl.configuration(), null));
        assertDomainViolation(() -> Routines.explainQueryPlan(dsl.configuration(), ""));
        assertDomainViolation(() -> Routines.explainQueryPlan(dsl.configuration(), "   "));
    }

    /**
     * A multi-statement string is rejected with {@code 22023} and its trailing
     * statements are not executed: PL/pgSQL's {@code EXECUTE} would otherwise run
     * them after planning the first one.
     */
    @Test
    void rejectsMultiStatementStringsWithoutExecutingThem() {
        assertSqlState("22023", () -> Routines.explainQueryPlan(dsl.configuration(),
                "SELECT 1; INSERT INTO dml_utils_fixtures.test_bigint (id, payload)"
                        + " VALUES (2, 'x')"));

        assertEquals(0, dsl.fetchCount(TEST_BIGINT),
                "the statement after the terminator must not run");
    }

    /**
     * The guard and the EXPLAIN agree even when the caller's session sets
     * standard_conforming_strings off: the routine pins it on, so a plain
     * literal containing a backslash still parses as closed. Without the pin,
     * the session's scs=off would leave the literal open and the EXPLAIN would
     * fail with 42601.
     *
     * <p>PostgreSQL 19 removed the non-standard setting, so the scenario is
     * skipped there; the pin itself is still valid in both cases.
     */
    @Test
    void pinsStandardConformingStringsForTheGuardAndTheExplain() {
        int serverVersion = dsl.fetchOne(
                        "SELECT pg_catalog.current_setting('server_version_num')::integer")
                .get(0, Integer.class);
        assumeTrue(serverVersion < 190000,
                "standard_conforming_strings = off was removed in PostgreSQL 19");

        dsl.transaction(config -> {
            org.jooq.DSLContext tx = org.jooq.impl.DSL.using(config);
            tx.execute("SET LOCAL standard_conforming_strings = off");

            assertEquals("Result", planNodeType(Routines.explainQueryPlan(
                    tx.configuration(), "SELECT 'a\\'")));
        });
    }

    /**
     * A statement that does not parse propagates PostgreSQL's syntax error
     * ({@code 42601}) rather than a misleading success.
     */
    @Test
    void propagatesSyntaxErrors() {
        assertSqlState("42601", () -> Routines.explainQueryPlan(dsl.configuration(), "SELECT FROM"));
    }

}
