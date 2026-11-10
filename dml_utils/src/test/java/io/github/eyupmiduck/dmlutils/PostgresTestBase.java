package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOrderedKey.TEST_ORDERED_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * dml_utils' PostgreSQL test base: the shared
 * {@link io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase}
 * configured for the dml_utils roles, schemas and changelog, plus the
 * dml_utils-specific fixture and run helpers.
 *
 * <p>The shared base starts one container, migrates a template database from
 * the production changelog and the dml_utils fixture changelog, and clones a
 * private database per test class as the {@code dml_utils_test} role.
 */
abstract class PostgresTestBase extends io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase {

    /**
     * The schema Liquibase keeps its tracking tables in, so they stay out of the
     * application schemas.
     */
    static final String LIQUIBASE_SCHEMA = "liquibase";
    static final String DATABASE_CHANGELOG_TABLE = "dml_utils_databasechangelog";
    static final String DATABASE_CHANGELOG_LOCK_TABLE = "dml_utils_databasechangeloglock";
    /**
     * The test-only fixture changelog (fixture tables the tests drive the
     * routines over), applied to the template database after the production
     * changelog.
     */
    static final String FIXTURES_RESOURCE = "db/changelog-fixtures/db.changelog-fixtures.xml";
    /**
     * The container configuration, shared by this base and
     * {@link LiquibaseUpgradeTest} so the two cannot drift (the container's
     * template database is prepared once per JVM from the first class that
     * runs, so an inconsistent configuration would be order-dependent).
     */
    static final String POSTGRES_IMAGE = "dml-utils-postgres:17-alpine";
    static final String DATABASE = "dml_utils";
    static final String OWNER_USER = "dml_utils_owner";
    static final String OWNER_PASSWORD = "dml_utils_owner";
    static final String TEST_USER = "dml_utils_test";
    static final String TEST_PASSWORD = "dml_utils_test";
    /**
     * The one container and changelog configuration, returned by this class and
     * by {@link LiquibaseUpgradeTest} so the two cannot drift.
     *
     * <p>The master changelog is the install changelog: ddl_utils first, then the
     * dml_utils changes, so the test database has the DDL helpers that future
     * migrations call.
     */
    static final Configuration CONFIGURATION = new Configuration(
            DATABASE, OWNER_USER, OWNER_PASSWORD, TEST_USER, TEST_PASSWORD,
            POSTGRES_IMAGE, "db/changelog-install/db.changelog-install.xml",
            DATABASE_CHANGELOG_TABLE, DATABASE_CHANGELOG_LOCK_TABLE, FIXTURES_RESOURCE,
            PostgresTestBase::installProjectExtensions);
    /**
     * The configuration for {@link LiquibaseUpgradeTest}. It differs from
     * {@link #CONFIGURATION} only in the master changelog: the upgrade test
     * compares dml_utils with its own previous release, so it must apply the
     * plain dml_utils changelog and not the install changelog that bundles
     * ddl_utils. Its template is still built from its own extension hook, which
     * pre-installs ddl_utils, so the roles, image, tracking tables and fixtures
     * stay shared.
     */
    static final Configuration UPGRADE_CONFIGURATION = new Configuration(
            DATABASE, OWNER_USER, OWNER_PASSWORD, TEST_USER, TEST_PASSWORD,
            POSTGRES_IMAGE, ChangelogTestSupport.MASTER_RESOURCE,
            DATABASE_CHANGELOG_TABLE, DATABASE_CHANGELOG_LOCK_TABLE, FIXTURES_RESOURCE,
            PostgresTestBase::installProjectExtensionsAndDdlUtils);

    /**
     * Returns a jOOQ field reference by name, for fixture columns when no
     * generated field is available.
     *
     * @param name the column name
     * @param type the column's Java type
     * @param <T>  the column type
     * @return the field reference
     */
    protected static <T> Field<T> field(String name, Class<T> type) {
        return DSL.field(name, type);
    }

    /**
     * Installs the extensions the changelog's routines need into a database,
     * and grants the roles they require. Shared with
     * {@link LiquibaseUpgradeTest}, whose scratch database needs the same
     * extensions before the changelog runs.
     *
     * @param admin a superuser connection to the target database
     * @throws SQLException if the extensions cannot be installed
     */
    static void installProjectExtensions(Connection admin) throws SQLException {
        try (java.sql.Statement statement = admin.createStatement()) {
            // plpgsql_check for static analysis; pg_background for the chunking
            // routines, which declare its types, so the extension must exist
            // when Liquibase creates them.
            statement.execute("CREATE EXTENSION IF NOT EXISTS plpgsql_check");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_background");
            // pg_background grants no access to PUBLIC; grant its role to the
            // owner and test roles (membership is cluster-wide).
            statement.execute("GRANT pgbackground_role TO " + OWNER_USER);
            statement.execute("GRANT pgbackground_role TO " + TEST_USER);
            // postgres_fdw lets the leaf-enumeration test attach a foreign-table
            // partition, which has no heap and must be rejected. The test creates
            // and drops the placeholder server itself.
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw");
            statement.execute("GRANT USAGE ON FOREIGN DATA WRAPPER postgres_fdw TO " + OWNER_USER);
            // ddl_utils is installed alongside dml_utils; its grants target
            // ddl_utils_caller, so create the role and let the dml_utils roles
            // call the DDL helpers.
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles "
                    + "WHERE rolname = 'ddl_utils_caller') THEN CREATE ROLE ddl_utils_caller; END IF; END $$;");
            statement.execute("GRANT ddl_utils_caller TO " + OWNER_USER);
            statement.execute("GRANT ddl_utils_caller TO " + TEST_USER);
        }
    }

    /**
     * Installs the staged ddl_utils changelog into the given database as a
     * superuser. The normal test template uses the install changelog, which
     * already includes ddl_utils; the upgrade test applies the previous release
     * first, so it installs ddl_utils separately so the current changelog can
     * call its helpers.
     *
     * @param admin a superuser connection to the target database
     * @throws SQLException if the changelog cannot be applied
     */
    static void installDdlUtils(Connection admin) throws SQLException {
        try (java.sql.Statement statement = admin.createStatement()) {
            // The image creates the liquibase schema for the dml_utils database;
            // the upgrade test's scratch database needs it too, owned by the role
            // that applies the previous release so Liquibase can create its
            // tracking table there.
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + LIQUIBASE_SCHEMA
                    + " AUTHORIZATION " + OWNER_USER);
            statement.execute("ALTER SCHEMA " + LIQUIBASE_SCHEMA + " OWNER TO " + OWNER_USER);
            statement.execute("GRANT CREATE, USAGE ON SCHEMA " + LIQUIBASE_SCHEMA
                    + " TO " + OWNER_USER);
        }
        try {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(admin));
            database.setDefaultSchemaName("public");
            database.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
            database.setDatabaseChangeLogTableName("ddl_utils_databasechangelog");
            database.setDatabaseChangeLogLockTableName("ddl_utils_databasechangeloglock");
            try {
                new Liquibase(
                        "db/changelog-install/ddl-utils/db/changelog/db.changelog-master.xml",
                        new ClassLoaderResourceAccessor(),
                        database).update();
            } finally {
                database.close();
            }
        } catch (Exception error) {
            throw new SQLException("could not install ddl_utils", error);
        }
    }

    /**
     * Installs the extensions and grants the template and upgrade scratch
     * databases need: the image's extensions and role grants, then the staged
     * ddl_utils changelog, so the upgrade test's plain dml_utils changelog can
     * call the DDL helpers.
     *
     * @param admin a superuser connection to the target database
     * @throws SQLException if an installation step fails
     */
    static void installProjectExtensionsAndDdlUtils(Connection admin) throws SQLException {
        installProjectExtensions(admin);
        installDdlUtils(admin);
    }

    /**
     * A synthetic uuid for the uuid-key fixtures, with the given value in its
     * final twelve digits. Shared so every test builds the same values.
     *
     * @param n the trailing value
     * @return the synthetic uuid
     */
    protected static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n));
    }

    /**
     * A synthetic timestamptz one second after 2026-01-01T00:00:00Z, for the
     * ordered-key fixtures. The value is formatted zero-padded so any second
     * count yields a valid instant.
     *
     * @param second the second within the first minute
     * @return the synthetic instant
     */
    protected static OffsetDateTime timestamp(int second) {
        return OffsetDateTime.parse(String.format("2026-01-01T00:00:%02dZ", second));
    }

    /**
     * Sets {@code standard_conforming_strings = off} on a session. PostgreSQL 19
     * removed non-standard string literals and rejects the setting, so only on a
     * 19 or newer server is a failure tolerated (there is nothing to be
     * independent of, and the session is left as it is); on an older server a
     * failed SET is a test failure, not a quietly weaker test.
     *
     * @param session a session on an autocommit connection
     * @return {@code true} when the setting took effect, {@code false} on a
     * server that no longer supports it
     */
    protected static boolean tryDisableStandardConformingStrings(DSLContext session) {
        try {
            session.execute("SET standard_conforming_strings = off");
            return true;
        } catch (DataAccessException unsupported) {
            int major = Integer.parseInt(
                    session.fetchOne("SHOW server_version_num").get(0, String.class)) / 10000;
            if (major < 19) {
                throw unsupported;
            }
            return false;
        }
    }

    /**
     * Returns the schema name of a fixture table.
     *
     * @param table the jOOQ table
     * @return the table's schema name
     */
    protected static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    /**
     * Returns the name of a fixture table.
     *
     * @param table the jOOQ table
     * @return the table's name
     */
    protected static String name(Table<?> table) {
        return table.getName();
    }

    /**
     * Returns the default bigint fixture's schema name.
     *
     * @return the schema name
     */
    protected static String schema() {
        return schema(TEST_BIGINT);
    }

    /**
     * Returns the default bigint fixture's table name.
     *
     * @return the table name
     */
    protected static String name() {
        return name(TEST_BIGINT);
    }

    /**
     * Asserts the two position-aligned parts of a {@code (timestamptz, bigint)}
     * boundary key.
     *
     * @param boundary the boundary row
     * @param second   the expected timestamp, in seconds after the fixture epoch
     * @param id       the expected bigint tiebreaker
     */
    protected static void assertTimestamptzBigint(MigrationBoundaryRecord boundary, int second,
                                                  long id) {
        assertEquals(timestamp(second).toInstant(),
                boundary.getBoundaryId().getTimestamptzValues()[0].toInstant(),
                "timestamptz position");
        assertEquals(id, boundary.getBoundaryId().getBigintValues()[1].longValue(),
                "bigint position");
    }

    /**
     * Sets the current role on a test connection.
     *
     * @param connection the connection
     * @param role       the role to assume
     * @throws SQLException if the role cannot be set
     */
    protected static void setRole(Connection connection, String role) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
        }
    }

    /**
     * Grants the test harness its extra setup privileges after the template
     * database is migrated.
     *
     * <p>The production changelog deliberately gives {@code dml_utils_caller}
     * read-only metadata access and delegates writes to owner-owned helpers. The
     * test role still needs direct metadata writes for fixture setup and
     * assertions, and needs the caller's application-table privileges so it can
     * exercise the invoker-owned chunk path. These are test-only grants: they are
     * applied per test database as the owner, not by the production changelog.
     */
    @BeforeEach
    void grantTestHarnessPrivileges() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE"
                    + " ON dml_utils_data.migration_run TO dml_utils_test");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE"
                    + " ON dml_utils_data.migration_boundary TO dml_utils_test");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE"
                    + " ON dml_utils_data.migration_error TO dml_utils_test");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE"
                    + " ON dml_utils_data.migration_run_leaf TO dml_utils_test");

            statement.execute("GRANT USAGE ON SCHEMA dml_utils_fixtures TO dml_utils_caller");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE"
                    + " ON ALL TABLES IN SCHEMA dml_utils_fixtures TO dml_utils_caller");
        }
    }

    @Override
    protected Configuration configuration() {
        return CONFIGURATION;
    }

    /**
     * Evaluates a SQL expression and returns its single value as the given
     * Java type.
     *
     * <p>This is plain SQL on purpose: testing a domain's check constraint
     * means casting a literal to the domain, and jOOQ renders a cast to a
     * {@code Domain}'s data type as the base type, so the constraint would not
     * be exercised.
     *
     * @param expression the SQL expression to evaluate
     * @param type       the Java type to read the value as
     * @param <T>        the value type
     * @return the expression's value
     */
    protected <T> T evaluate(String expression, Class<T> type) {
        Record record = dsl.fetchOne("SELECT " + expression);
        return record.get(0, type);
    }

    /**
     * Reads the top-level plan node type from an {@code EXPLAIN (FORMAT JSON)}
     * document, so plan-content tests assert a statement's shape without
     * duplicating the json path.
     *
     * @param plan the {@code EXPLAIN (FORMAT JSON)} document
     * @return the plan's {@code Node Type}
     */
    protected String planNodeType(JSON plan) {
        return dsl.fetchOne("SELECT (?::json -> 0 -> 'Plan' ->> 'Node Type')",
                plan.data()).get(0, String.class);
    }

    /**
     * Inserts one row per id into {@code dml_utils_fixtures.test_bigint}, the
     * single-bigint-key fixture the routines are driven over.
     *
     * @param ids the primary-key values to insert
     */
    protected void seedBigint(long... ids) {
        for (long id : ids) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
    }

    /**
     * Seeds the five-row ordered-key fixture: timestamps repeat in pairs, so
     * the bigint column is the tiebreaker.
     */
    protected void seedOrderedKey() {
        for (int i = 1; i <= 5; i++) {
            dsl.insertInto(TEST_ORDERED_KEY,
                            TEST_ORDERED_KEY.ID, TEST_ORDERED_KEY.CREATED_AT,
                            TEST_ORDERED_KEY.EXTERNAL_ID, TEST_ORDERED_KEY.LABEL,
                            TEST_ORDERED_KEY.COLLATED_LABEL, TEST_ORDERED_KEY.NUMERIC_KEY)
                    .values((long) i, timestamp((i + 1) / 2), uuid(i), "k" + i, "k" + i,
                            BigDecimal.valueOf(i))
                    .execute();
        }
    }

    /**
     * A per-invocation unique run label: {@code prefix-UUID}, so tests never
     * collide on the one-active-run-per-label rule regardless of order.
     *
     * @param prefix a short descriptive prefix
     * @return a unique label
     */
    protected String uniqueLabel(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * Returns the id of the run for a label.
     *
     * @param label the run label
     * @return the run id
     */
    protected long runId(String label) {
        return dsl.select(MIGRATION_RUN.RUN_ID)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    /**
     * Returns the run's total chunk count.
     *
     * @param runId the run id
     * @return the total chunk count
     */
    protected long totalChunks(long runId) {
        return dsl.select(MIGRATION_RUN.TOTAL_CHUNKS)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_CHUNKS);
    }

    /**
     * Returns the run's completed chunk count.
     *
     * @param runId the run id
     * @return the completed chunk count
     */
    protected long totalChunksCompleted(long runId) {
        return dsl.select(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED);
    }

    /**
     * Counts the run's completed boundaries.
     *
     * @param runId the run id
     * @return the number of boundaries with {@code completed_at} set
     */
    protected int completedBoundaries(long runId) {
        return dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId).and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
    }

    /**
     * Resolves a schema-qualified relation name to its OID.
     *
     * @param qualifiedName the schema-qualified relation name
     * @return the relation OID
     */
    protected long relationOid(String qualifiedName) {
        return dsl.fetchOne("SELECT ?::regclass::oid", qualifiedName)
                .get(0, Long.class);
    }

    /**
     * Returns the run's boundaries in boundary order.
     *
     * @param runId the run id
     * @return the boundary rows
     */
    protected List<MigrationBoundaryRecord> boundaries(long runId) {
        return dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
    }

    /**
     * Inserts a run for a table with a generated label and returns its id.
     *
     * @param table the driving table
     * @return the run id
     */
    protected long insertRun(Table<?> table) {
        return insertRun("test-run", table);
    }

    /**
     * Inserts a run for a table with a generated label prefix.
     *
     * @param labelPrefix the label prefix
     * @param table       the driving table
     * @return the run id
     */
    protected long insertRun(String labelPrefix, Table<?> table) {
        return insertRun(labelPrefix + "-" + UUID.randomUUID(), schema(table), name(table));
    }

    /**
     * Inserts a run with an exact label and driving-table coordinates.
     *
     * @param label      the run label
     * @param schemaName the driving table's schema
     * @param tableName  the driving table's name
     * @return the run id
     */
    protected long insertRun(String label, String schemaName, String tableName) {
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values(label, "SELECT 1", 1, 1, schemaName, tableName)
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    /**
     * Counts the rows of a fixture table whose {@code payload} equals a value.
     *
     * @param table the fixture table
     * @param value the expected payload
     * @return the matching row count
     */
    protected int payloadCount(Table<?> table, String value) {
        return dsl.fetchCount(table, field("payload", String.class).eq(value));
    }

    /**
     * Whether the run with the given id has completed.
     *
     * @param runId the run id
     * @return {@code true} when {@code completed_at} is set
     */
    protected boolean runCompleted(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.COMPLETED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT.isNotNull()));
    }

    /**
     * Whether the run with the given label has completed.
     *
     * @param label the run label
     * @return {@code true} when {@code completed_at} is set
     */
    protected boolean runCompleted(String label) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.COMPLETED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT.isNotNull()));
    }

    /**
     * Whether the run with the given id has started processing.
     *
     * @param runId the run id
     * @return {@code true} when {@code started_at} is set
     */
    protected boolean runStarted(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.STARTED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.STARTED_AT.isNotNull()));
    }

    /**
     * Whether the boundary {@code (runId, boundaryNo)} has been claimed.
     *
     * @param runId      the run id
     * @param boundaryNo the boundary number
     * @return {@code true} when {@code completed_at} is set
     */
    protected boolean boundaryCompleted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo)))
                .fetchOne(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
    }

    /**
     * Whether the boundary {@code (runId, boundaryNo)} has been claimed and
     * started processing.
     *
     * @param runId      the run id
     * @param boundaryNo the boundary number
     * @return {@code true} when {@code started_at} is set
     */
    protected boolean boundaryStarted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.STARTED_AT.isNotNull())
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo)))
                .fetchOne(MIGRATION_BOUNDARY.STARTED_AT.isNotNull()));
    }
}
