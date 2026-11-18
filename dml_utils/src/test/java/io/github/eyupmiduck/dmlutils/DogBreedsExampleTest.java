package io.github.eyupmiduck.dmlutils;

import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the runnable examples in {@code examples/} against the test database.
 * Each script must complete its run, backfill every seeded row, and — on a second
 * run — report only the current run, with no duplicated boundary numbers.
 */
class DogBreedsExampleTest extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of("..", "examples");
    private static final int SEEDED_BREEDS = 52;

    /**
     * The table-driven example backfills all rows and reruns cleanly. The second
     * run is what exercises the archived-run filters the example now uses.
     */
    @Test
    void dogBreedsExampleBackfillsAndRerunsCleanly() throws Exception {
        runExample("dog_breeds");
        assertExampleOutcome("dog_breeds", "dogs-status-backfill");
        runExample("dog_breeds");
        assertExampleOutcome("dog_breeds", "dogs-status-backfill");
    }

    /**
     * The function-over-table example backfills all rows and reruns cleanly, and
     * its summary and boundary queries report only the current run.
     */
    @Test
    void dogBreedsFunctionExampleBackfillsAndRerunsCleanly() throws Exception {
        runExample("dog_breeds_function");
        assertExampleOutcome("dog_breeds_function", "dogs-review-function");
        runExample("dog_breeds_function");
        assertExampleOutcome("dog_breeds_function", "dogs-review-function");
    }

    /**
     * The function-over-table example creates its objects in its own
     * {@code dog_breeds_function} schema: a pre-existing {@code public.dogs}
     * table (which the script used to drop) is left untouched.
     */
    @Test
    void dogBreedsFunctionExampleDoesNotTouchPublicTables() throws Exception {
        String database = dsl.fetchOne("SELECT current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE public.dogs (breed text PRIMARY KEY)");
            statement.execute("INSERT INTO public.dogs VALUES ('sentinel')");

            runExample("dog_breeds_function");

            // Read through the admin connection: dml_utils_test has no grants on
            // a table the example must never reference.
            try (var rows = statement.executeQuery(
                    "SELECT count(*), min(breed) FROM public.dogs")) {
                assertTrue(rows.next(), "the sentinel query returns a row");
                assertEquals(1, rows.getInt(1),
                        "the example must not touch a public.dogs table");
                assertEquals("sentinel", rows.getString(2),
                        "the public.dogs row is unchanged");
            }
        }
    }

    /**
     * Executes one example script as the superuser (the README's instructions),
     * after dropping the psql meta-commands ({@code \set}, {@code \echo}) that
     * JDBC cannot run. The cloned test database does not let the owner create
     * schemas, and the example creates one.
     *
     * <p>Each statement runs on its own (autocommit, like psql): the migration
     * worker is a separate session, so it must see the committed schema, table and
     * seed rows. Running the whole script as one multi-statement query would put
     * them in one implicit transaction the worker cannot see.
     *
     * @param directory the example directory, which also names the script
     * @throws Exception if the script cannot be read or executed
     */
    private void runExample(String directory) throws Exception {
        Path script = EXAMPLES.resolve(directory).resolve(directory + ".sql");
        String sql = Files.readString(script).lines()
                .filter(line -> !line.stripLeading().startsWith("\\"))
                .collect(Collectors.joining("\n"));

        String database = dsl.fetchOne("SELECT current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            for (String single : splitStatements(sql)) {
                statement.execute(single);
            }
        }
    }

    /**
     * Asserts the example's observable outcome: every seeded row is backfilled,
     * exactly one current run is reported, its boundaries are complete and
     * unique, and the run itself completed.
     *
     * @param schema the schema holding the example's {@code dogs} table
     * @param label  the run label the example uses
     */
    private void assertExampleOutcome(String schema, String label) {
        assertEquals(SEEDED_BREEDS, dsl.fetchOne("SELECT count(*) FROM " + schema
                        + ".dogs WHERE status = 'reviewed'").get(0, Integer.class),
                "every seeded breed is backfilled");

        Record summary = dsl.fetchOne("""
                SELECT count(*) AS runs,
                       bool_and(completed_at IS NOT NULL) AS all_completed
                FROM dml_utils.migration_run_summary(?)
                WHERE archived_at IS NULL
                """, label);
        assertEquals(1, summary.get("runs", Integer.class),
                "the example reports only the current run");
        assertTrue(summary.get("all_completed", Boolean.class),
                "the current run completed");

        // The terminal high-water boundary has no chunk to complete, so every
        // boundary must be completed or be the terminal one.
        Record boundaries = dsl.fetchOne("""
                WITH bounds AS (
                    SELECT b.boundary_no,
                           b.completed_at,
                           max(b.boundary_no) OVER () AS max_no
                    FROM dml_utils_data.migration_run AS r
                             JOIN dml_utils_data.migration_boundary AS b ON b.run_id = r.run_id
                    WHERE r.label = ?
                      AND r.archived_at IS NULL)
                SELECT count(*) AS total,
                       count(DISTINCT boundary_no) AS distinct_no,
                       bool_and(completed_at IS NOT NULL OR boundary_no = max_no)
                           AS all_chunks_completed
                FROM bounds
                """, label);
        int total = boundaries.get("total", Integer.class);
        assertTrue(total > 0, "the example stores chunk boundaries");
        assertEquals(total, boundaries.get("distinct_no", Integer.class),
                "the boundary query has no duplicate boundary_no values");
        assertTrue(boundaries.get("all_chunks_completed", Boolean.class),
                "every chunk boundary completed (the terminal boundary has no chunk)");
    }
}
