package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the install changelog's role bootstrap: it creates
 * {@code ddl_utils_caller} when the connecting user is a superuser, and a
 * concurrent install in another database of the same cluster does not fail on
 * the duplicate.
 */
class InstallRoleBootstrapTest extends PostgresTestBase {

    private static final String BOOTSTRAP =
            "db/changelog-install/sql/install-create-ddl-utils-caller.sql";

    /**
     * Waits until the given backend is blocked on the role insert, so the winner
     * cannot commit before the loser has passed its {@code NOT EXISTS} check.
     *
     * @param observer the admin connection used to read {@code pg_stat_activity}
     *                 (a non-superuser cannot see another backend's wait state)
     * @param pid      the loser's backend pid
     * @throws SQLException         if the polling query fails
     * @throws InterruptedException if the poll is interrupted
     */
    private static void awaitRoleLock(Connection observer, int pid)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try (Statement statement = observer.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT count(*) FROM pg_catalog.pg_stat_activity"
                                 + " WHERE pid = " + pid + " AND wait_event_type = 'Lock'")) {
                rows.next();
                if (rows.getInt(1) > 0) {
                    return;
                }
            }
            if (System.nanoTime() > deadline) {
                fail("the second bootstrap never blocked on the role insert");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Whether the role exists in the cluster.
     *
     * @param observer a connection to query with
     * @param role     the role name
     * @return true when {@code pg_roles} has the role
     * @throws SQLException if the query fails
     */
    private static boolean roleExists(Connection observer, String role) throws SQLException {
        try (var statement = observer.prepareStatement(
                "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }

    /**
     * Returns a connection's backend pid.
     *
     * @param connection the connection to identify
     * @return its backend pid
     * @throws SQLException if the query fails
     */
    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rows.next(), "pg_backend_pid() returns a row");
            return rows.getInt(1);
        }
    }

    /**
     * Runs one statement on the connection.
     *
     * @param connection the connection
     * @param sql        the statement
     * @throws SQLException if it fails
     */
    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * The loser of the role race succeeds: its {@code NOT EXISTS} passes while the
     * winner's {@code CREATE ROLE} is uncommitted, the insert then hits
     * {@code pg_authid}'s unique index and must be absorbed rather than failing
     * the install.
     *
     * <p>The script's role name is replaced with a unique one so the race is
     * isolated from the cluster's real role and from the template database's
     * grants; the bootstrap logic under test is unchanged.
     *
     * @throws Exception if the connections, the racer or the cleanup fail
     */
    @Test
    void concurrentBootstrapDoesNotFailTheInstall() throws Exception {
        String database = dsl.fetchOne("SELECT current_database()").get(0, String.class);
        String role = "ddl_race_" + UUID.randomUUID().toString().replace("-", "");
        String sql = TestResources.read(BOOTSTRAP).replace("ddl_utils_caller", role);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection winner = openAdminConnection(database);
             Connection loser = openAdminConnection(database);
             Connection observer = openAdminConnection(database)) {
            int loserPid = backendPid(loser);
            AtomicReference<Throwable> loserError = new AtomicReference<>();

            // The winner creates the role but keeps its transaction open, so the
            // loser's check cannot see it.
            winner.setAutoCommit(false);
            execute(winner, sql);

            Future<?> call = executor.submit(() -> {
                try {
                    execute(loser, sql);
                } catch (Throwable error) {
                    loserError.set(error);
                }
            });
            try {
                awaitRoleLock(observer, loserPid);
                winner.commit();
                call.get(10, TimeUnit.SECONDS);
                assertTrue(roleExists(observer, role),
                        "the install created the role");
            } finally {
                winner.rollback();
                try {
                    call.get(5, TimeUnit.SECONDS);
                } catch (Exception stillRunning) {
                    // The failure path below reports the real problem; do not let
                    // the cleanup replace it.
                }
                dropRole(database, role);
            }

            assertNull(loserError.get(),
                    () -> "a concurrent install failed: " + loserError.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "the racing install finished");
        }
    }

    /**
     * Drops the probe role on its own autocommit connection, so a pending winner
     * transaction cannot leave it behind.
     *
     * @param database the database to connect to
     * @param role     the probe role
     * @throws SQLException if the drop fails
     */
    private void dropRole(String database, String role) throws SQLException {
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS " + role);
        }
    }
}
