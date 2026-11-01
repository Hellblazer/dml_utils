package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.MigrationKeyPairValues;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.migration_key_pair_values}: it flattens a chunk's
 * start and end boundary keys together, position-aligned and in key order, and
 * rejects malformed kinds.
 */
class MigrationKeyPairValuesTest extends PostgresTestBase {

    /**
     * A single-column bigint key yields the start and end values.
     */
    @Test
    void flattensBothBoundsForABigintKey() {
        MigrationKeyPairValues values = Routines.migrationKeyPairValues(
                dsl.configuration(),
                new MigrationKeyRecord(new Long[]{1L}, null, null, null),
                new MigrationKeyRecord(new Long[]{10L}, null, null, null),
                new String[]{"bigint"});

        assertArrayEquals(new String[]{"1"}, values.getOStartValues());
        assertArrayEquals(new String[]{"10"}, values.getOEndValues());
    }

    /**
     * A two-column, mixed-kind key keeps each bound's values at their key
     * positions.
     */
    @Test
    void flattensBothBoundsForAMixedCompositeKey() {
        MigrationKeyPairValues values = Routines.migrationKeyPairValues(
                dsl.configuration(),
                new MigrationKeyRecord(new Long[]{1L, null}, new String[]{null, "x"}, null, null),
                new MigrationKeyRecord(new Long[]{2L, null}, new String[]{null, "y"}, null, null),
                new String[]{"bigint", "text"});

        assertArrayEquals(new String[]{"1", "x"}, values.getOStartValues());
        assertArrayEquals(new String[]{"2", "y"}, values.getOEndValues());
    }

    /**
     * A timestamptz key yields the start and end values as text that casts back
     * to the original instants.
     */
    @Test
    void flattensBothBoundsForATimestamptzKey() {
        OffsetDateTime start = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-01-02T00:00:00Z");

        MigrationKeyPairValues values = Routines.migrationKeyPairValues(
                dsl.configuration(),
                new MigrationKeyRecord(null, null, null, new OffsetDateTime[]{start}),
                new MigrationKeyRecord(null, null, null, new OffsetDateTime[]{end}),
                new String[]{"timestamptz"});

        assertEquals(start.toInstant(), dsl.fetchOne("SELECT ?::timestamptz", values.getOStartValues()[0])
                .get(0, OffsetDateTime.class).toInstant());
        assertEquals(end.toInstant(), dsl.fetchOne("SELECT ?::timestamptz", values.getOEndValues()[0])
                .get(0, OffsetDateTime.class).toInstant());
    }

    /**
     * An absent array or a NULL element at a position yields a NULL value there,
     * keeping the result's arity instead of shifting the later values.
     */
    @Test
    void keepsTheArityForAbsentOrNullPositions() {
        MigrationKeyPairValues values = Routines.migrationKeyPairValues(
                dsl.configuration(),
                new MigrationKeyRecord(null, null, null, null),
                new MigrationKeyRecord(new Long[]{null}, null, null, null),
                new String[]{"bigint"});

        assertArrayEquals(new String[]{null}, values.getOStartValues());
        assertArrayEquals(new String[]{null}, values.getOEndValues());
    }

    /**
     * An unsupported kind and an empty or NULL kinds array are rejected with
     * {@code 22023}.
     */
    @Test
    void rejectsUnsupportedOrEmptyKinds() {
        MigrationKeyRecord key = new MigrationKeyRecord(new Long[]{1L}, null, null, null);

        assertSqlState("22023", () -> Routines.migrationKeyPairValues(
                dsl.configuration(), key, key, new String[]{"numeric"}));
        assertSqlState("22023", () -> Routines.migrationKeyPairValues(
                dsl.configuration(), key, key, new String[]{}));
        assertSqlState("22023", () -> Routines.migrationKeyPairValues(
                dsl.configuration(), key, key, null));
    }
}
