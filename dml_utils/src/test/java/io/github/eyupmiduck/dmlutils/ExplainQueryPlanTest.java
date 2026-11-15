package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.JSON;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.explain_query_plan}: it returns the
 * {@code EXPLAIN (FORMAT JSON)} plan of the supplied statement without executing
 * it, and rejects a NULL or empty statement.
 */
class ExplainQueryPlanTest extends PostgresTestBase {

    /**
     * A SELECT is planned and the json carries the top-level plan node.
     */
    @Test
    void explainsASelect() {
        JSON plan = Routines.explainQueryPlan(dsl.configuration(), "SELECT 1");

        assertEquals("Result", planNodeType(plan));
    }

    /**
     * An UPDATE is planned as a ModifyTable and is not executed: the fixture
     * table stays empty.
     */
    @Test
    void explainsAnUpdateWithoutExecutingIt() {
        JSON plan = Routines.explainQueryPlan(dsl.configuration(),
                "UPDATE dml_utils_fixtures.test_bigint SET payload = 'x'");

        assertEquals("ModifyTable", planNodeType(plan));
        assertEquals(0, dsl.fetchCount(TEST_BIGINT), "EXPLAIN must not run the update");
    }

    /**
     * An INSERT is planned as a ModifyTable and is not executed: no row is
     * inserted.
     */
    @Test
    void explainsAnInsertWithoutExecutingIt() {
        JSON plan = Routines.explainQueryPlan(dsl.configuration(),
                "INSERT INTO dml_utils_fixtures.test_bigint (id, payload) VALUES (1, 'x')");

        assertEquals("ModifyTable", planNodeType(plan));
        assertEquals(0, dsl.fetchCount(TEST_BIGINT), "EXPLAIN must not run the insert");
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
     * A statement that does not parse propagates PostgreSQL's syntax error
     * ({@code 42601}) rather than a misleading success.
     */
    @Test
    void propagatesSyntaxErrors() {
        assertSqlState("42601", () -> Routines.explainQueryPlan(dsl.configuration(), "SELECT FROM"));
    }

}
