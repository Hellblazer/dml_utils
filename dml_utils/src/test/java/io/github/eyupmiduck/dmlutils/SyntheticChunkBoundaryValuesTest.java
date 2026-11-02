package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.SyntheticChunkBoundaryValuesRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.synthetic_chunk_boundary_values}: it returns one
 * non-final and one final representative chunk range, position-aligned with the
 * key kinds and in key order, and rejects malformed kinds or a non-positive
 * chunk size.
 */
class SyntheticChunkBoundaryValuesTest extends PostgresTestBase {

    /**
     * A single bigint key yields two rows whose range spans one chunk
     * ({@code 0 .. chunk_size}) and whose only difference is the final flag.
     */
    @Test
    void returnsNonFinalAndFinalRowsForABigintKey() {
        List<SyntheticChunkBoundaryValuesRecord> rows = Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"bigint"}, 10L);

        assertEquals(2, rows.size(), "one non-final and one final row");
        assertFalse(rows.get(0).getOIsFinal(), "the first row is non-final");
        assertTrue(rows.get(1).getOIsFinal(), "the second row is final");
        assertArrayEquals(new String[]{"0"}, rows.get(0).getOStartValues());
        assertArrayEquals(new String[]{"10"}, rows.get(0).getOEndValues());
        assertArrayEquals(rows.get(0).getOStartValues(), rows.get(1).getOStartValues(),
                "the final row reuses the same start");
        assertArrayEquals(rows.get(0).getOEndValues(), rows.get(1).getOEndValues(),
                "the final row reuses the same end");
    }

    /**
     * Text and uuid keys each get a fixed pair whose start sorts before its end.
     */
    @Test
    void returnsPairsForTextAndUuidKeys() {
        SyntheticChunkBoundaryValuesRecord text = Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"text"}, 10L).get(0);
        assertArrayEquals(new String[]{"a"}, text.getOStartValues());
        assertArrayEquals(new String[]{"b"}, text.getOEndValues());

        SyntheticChunkBoundaryValuesRecord uuid = Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"uuid"}, 10L).get(0);
        assertArrayEquals(new String[]{"00000000-0000-0000-0000-000000000000"},
                uuid.getOStartValues());
        assertArrayEquals(new String[]{"00000000-0000-0000-0000-000000000001"},
                uuid.getOEndValues());
    }

    /**
     * A timestamptz key gets a fixed pair of instants whose start sorts before
     * its end.
     */
    @Test
    void returnsAPairForATimestamptzKey() {
        SyntheticChunkBoundaryValuesRecord timestamptz = Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"timestamptz"}, 10L).get(0);
        assertArrayEquals(new String[]{"2026-01-01T00:00:00Z"},
                timestamptz.getOStartValues());
        assertArrayEquals(new String[]{"2026-01-02T00:00:00Z"},
                timestamptz.getOEndValues());
    }

    /**
     * A composite key aligns each position with its own kind, so a bigint and a
     * text column get the bigint and text pairs respectively.
     */
    @Test
    void alignsEachPositionWithItsKindForCompositeKeys() {
        SyntheticChunkBoundaryValuesRecord row = Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"bigint", "text"}, 5L).get(0);

        assertArrayEquals(new String[]{"0", "a"}, row.getOStartValues());
        assertArrayEquals(new String[]{"5", "b"}, row.getOEndValues());
    }

    /**
     * A null, empty, over-long or unknown kinds array is rejected with
     * {@code 22023}.
     */
    @Test
    void rejectsMalformedKeyKinds() {
        assertSqlState("22023", () -> Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), null, 10L));
        assertSqlState("22023", () -> Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{}, 10L));
        assertSqlState("22023", () -> Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"bigint", "bigint", "bigint", "bigint"}, 10L));
        assertSqlState("22023", () -> Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"numeric"}, 10L));
    }

    /**
     * A non-positive chunk size is rejected with {@code 22023}: it would render
     * an empty or reversed bigint range.
     */
    @Test
    void rejectsANonPositiveChunkSize() {
        assertSqlState("22023", () -> Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"bigint"}, 0L));
        assertSqlState("22023", () -> Routines.syntheticChunkBoundaryValues(
                dsl.configuration(), new String[]{"bigint"}, -1L));
    }
}
