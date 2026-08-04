package io.github.jacekkardys.systemproof.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements.Table;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.Setting;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.TablePersistence;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.TableTriggers;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Performs typed, fail-closed durability checks without retaining connection details. */
public final class PostgresqlDurabilityVerifier {
    private PostgresqlDurabilityVerifier() {}

    /**
     * Verifies server settings, backend independence, and table persistence.
     *
     * <p>The caller must supply a SUT connection and a separately opened verification connection.
     * The result contains only typed facts; SQL exceptions are converted to a secret-safe failure.
     *
     * @param sutConnection connection used by the system under test
     * @param verificationConnection independently opened verification connection
     * @param requirements permanent tables required by the scenario
     * @return immutable typed prerequisite result
     */
    public static PostgresqlDurabilityResult verify(
        Connection sutConnection,
        Connection verificationConnection,
        PostgresqlDurabilityRequirements requirements
    ) {
        Objects.requireNonNull(sutConnection, "sutConnection must not be null");
        Objects.requireNonNull(
            verificationConnection,
            "verificationConnection must not be null"
        );
        Objects.requireNonNull(requirements, "requirements must not be null");
        return inspect(sutConnection, verificationConnection, requirements);
    }

    /**
     * Verifies prerequisites without authorizing durable success.
     *
     * @deprecated exact-route authorization requires a {@link ConnectionId}
     */
    @Deprecated(forRemoval = false)
    public static PostgresqlDurabilityResult verify(
        Connection sutConnection,
        Connection verificationConnection,
        PostgresqlDurabilityRequirements requirements,
        PostgresqlProtocolAdapter adapter
    ) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        return inspect(sutConnection, verificationConnection, requirements);
    }

    /**
     * Verifies prerequisites and authorizes only the exact observed transaction on one route.
     *
     * <p>A one-shot opaque value is sent through {@code sutConnection}. The adapter can claim it
     * only from a physical protocol session opened for {@code connectionId}. Authorization is
     * sent only after all exact-SUT and independent checks complete. Authorization is applied
     * immediately after that round trip, and the next SQL command must be the commit for the
     * claimed transaction.
     */
    public static PostgresqlDurabilityResult verify(
        Connection sutConnection,
        Connection verificationConnection,
        PostgresqlDurabilityRequirements requirements,
        PostgresqlProtocolAdapter adapter,
        ConnectionId connectionId
    ) {
        Objects.requireNonNull(sutConnection, "sutConnection must not be null");
        Objects.requireNonNull(
            verificationConnection,
            "verificationConnection must not be null"
        );
        Objects.requireNonNull(requirements, "requirements must not be null");
        Objects.requireNonNull(adapter, "adapter must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        PostgresqlDurabilityResult result = inspect(
            sutConnection,
            verificationConnection,
            requirements
        );
        try (PostgresqlProtocolAdapter.DurabilityChallenge challenge =
                 adapter.beginDurabilityChallenge(connectionId)) {
            executeChallenge(sutConnection, challenge.token());
            boolean observed = adapter.applyDurability(
                challenge,
                result.satisfied()
            );
            if (!observed) {
                throw new IllegalStateException(
                    "PostgreSQL SUT transaction is not observed on the required connection"
                );
            }
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException(
                "PostgreSQL durability verification could not complete"
            );
        }
    }

    private static PostgresqlDurabilityResult inspect(
        Connection sutConnection,
        Connection verificationConnection,
        PostgresqlDurabilityRequirements requirements
    ) {
        Objects.requireNonNull(sutConnection, "sutConnection must not be null");
        Objects.requireNonNull(
            verificationConnection,
            "verificationConnection must not be null"
        );
        Objects.requireNonNull(requirements, "requirements must not be null");
        try {
            Setting synchronousCommit = setting(sutConnection, "SHOW synchronous_commit");
            Setting sutFsync = setting(sutConnection, "SHOW fsync");
            Setting verificationFsync = setting(verificationConnection, "SHOW fsync");
            int sutBackendPid = backendPid(sutConnection);
            boolean independent = sutConnection != verificationConnection
                && sutBackendPid != backendPid(verificationConnection);
            Map<Table, TablePersistence> sutTables = new LinkedHashMap<>();
            Map<Table, TablePersistence> verificationTables = new LinkedHashMap<>();
            Map<Table, TableTriggers> sutTriggers = new LinkedHashMap<>();
            Map<Table, TableTriggers> verificationTriggers = new LinkedHashMap<>();
            for (Table table : requirements.tables()) {
                sutTables.put(table, persistence(sutConnection, table));
                verificationTables.put(table, persistence(verificationConnection, table));
                sutTriggers.put(table, triggers(sutConnection, table));
                verificationTriggers.put(table, triggers(verificationConnection, table));
            }
            boolean consistent = sutFsync == verificationFsync
                && sutTables.equals(verificationTables)
                && sutTriggers.equals(verificationTriggers);
            return new PostgresqlDurabilityResult(
                synchronousCommit,
                sutFsync,
                independent,
                consistent,
                sutTables,
                sutTriggers
            );
        } catch (SQLException failure) {
            throw new IllegalStateException(
                "PostgreSQL durability verification could not complete"
            );
        }
    }

    private static void executeChallenge(Connection connection, String token)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?::text")) {
            statement.setString(1, token);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !token.equals(result.getString(1)) || result.next()) {
                    throw new SQLException("Durability challenge did not round-trip exactly");
                }
            }
        }
    }

    private static Setting setting(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            if (!result.next()) {
                return Setting.OTHER;
            }
            return switch (result.getString(1).toLowerCase(Locale.ROOT)) {
                case "on" -> Setting.ON;
                case "off" -> Setting.OFF;
                default -> Setting.OTHER;
            };
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            if (!result.next()) {
                throw new SQLException("Backend identity was unavailable");
            }
            return result.getInt(1);
        }
    }

    private static TablePersistence persistence(Connection connection, Table table)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT c.relpersistence
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
            """)) {
            statement.setString(1, table.schema());
            statement.setString(2, table.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return TablePersistence.MISSING;
                }
                return switch (result.getString(1)) {
                    case "p" -> TablePersistence.PERMANENT;
                    case "u" -> TablePersistence.UNLOGGED;
                    case "t" -> TablePersistence.TEMPORARY;
                    default -> TablePersistence.OTHER;
                };
            }
        }
    }

    private static TableTriggers triggers(Connection connection, Table table)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND NOT t.tgisinternal
                  AND t.tgenabled <> 'D'
            )
            """)) {
            statement.setString(1, table.schema());
            statement.setString(2, table.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Table trigger status was unavailable");
                }
                return result.getBoolean(1)
                    ? TableTriggers.ENABLED
                    : TableTriggers.NONE_ENABLED;
            }
        }
    }
}
