package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.PrimaryKeyAttributesRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@code dml_utils_lib.primary_key_attributes}: it returns the primary
 * key columns in key order with, per column, the ordinality, name and collapsed
 * boundary kind, and rejects a missing schema/table, a table without a primary
 * key and a key of more than three columns.
 */
class PrimaryKeyAttributesTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";

    private static void assertAttribute(PrimaryKeyAttributesRecord row, int ordinality, String name, String kind) {
        assertEquals(ordinality, row.getOrdinality());
        assertEquals(name, row.getColumnName());
        assertEquals(kind, row.getKeyKind());
    }

    /**
     * A three-column key is returned in key order (not physical column order)
     * with each column's collapsed kind.
     */
    @Test
    void returnsKeyColumnsInKeyOrderWithKinds() {
        List<PrimaryKeyAttributesRecord> rows = Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_composite_three");

        assertEquals(3, rows.size());
        assertAttribute(rows.get(0), 1, "b", "bigint");
        assertAttribute(rows.get(1), 2, "a", "text");
        assertAttribute(rows.get(2), 3, "c", "uuid");
    }

    /**
     * The whole integer family collapses to the {@code bigint} boundary kind.
     */
    @Test
    void collapsesTheIntegerFamilyToBigint() {
        for (String table : List.of("test_smallint", "test_integer", "test_bigint")) {
            List<PrimaryKeyAttributesRecord> rows = Routines.primaryKeyAttributes(
                    dsl.configuration(), FIXTURE_SCHEMA, table);
            assertEquals(1, rows.size(), table + " has a one-column key");
            assertEquals("bigint", rows.get(0).getKeyKind(), table);
        }
    }

    /**
     * A key column of an unsupported type is returned with a NULL kind (the
     * caller decides whether that is an error).
     */
    @Test
    void returnsANullKindForAnUnsupportedType() {
        List<PrimaryKeyAttributesRecord> rows = Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_numeric");

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getKeyKind(), "a numeric key has no supported kind");
    }

    /**
     * A timestamptz primary key maps to the {@code timestamptz} boundary kind.
     */
    @Test
    void mapsATimestamptzPrimaryKey() {
        List<PrimaryKeyAttributesRecord> rows = Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_timestamptz_pk");

        assertEquals(1, rows.size());
        assertAttribute(rows.get(0), 1, "created_at", "timestamptz");
    }

    /**
     * A composite (timestamptz, bigint) key keeps the key order and each
     * column's collapsed kind.
     */
    @Test
    void returnsACompositeTimestamptzKeyInKeyOrder() {
        List<PrimaryKeyAttributesRecord> rows = Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_timestamptz_composite_pk");

        assertEquals(2, rows.size());
        assertAttribute(rows.get(0), 1, "created_at", "timestamptz");
        assertAttribute(rows.get(1), 2, "id", "bigint");
    }

    /**
     * A table without a primary key raises {@code 22023}.
     */
    @Test
    void rejectsATableWithoutAPrimaryKey() {
        assertSqlState("22023", () -> Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_no_pk"));
    }

    /**
     * A primary key of more than three columns raises {@code 22023}.
     */
    @Test
    void rejectsAPrimaryKeyOverThreeColumns() {
        assertSqlState("22023", () -> Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "test_composite_four"));
    }

    /**
     * A missing schema or table raises {@code 3F000} / {@code 42P01}.
     */
    @Test
    void rejectsAMissingSchemaOrTable() {
        assertSqlState("3F000", () -> Routines.primaryKeyAttributes(
                dsl.configuration(), "no_such_schema", "test_bigint"));
        assertSqlState("42P01", () -> Routines.primaryKeyAttributes(
                dsl.configuration(), FIXTURE_SCHEMA, "no_such_table"));
    }
}
