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
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.RelationStatus;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.Setting;

/** Performs a typed, fail-closed durability preflight on a test-owned JDBC connection. */
public final class PostgresqlDurabilityVerifier {
    private PostgresqlDurabilityVerifier() {}

    /**
     * Verifies configured server/role settings and required schema-qualified relations before
     * proof traffic. The supplied connection must be independently owned by the test.
     */
    public static PostgresqlDurabilityResult verify(
        Connection verificationConnection,
        PostgresqlDurabilityRequirements requirements
    ) {
        Objects.requireNonNull(
            verificationConnection,
            "verificationConnection must not be null"
        );
        Objects.requireNonNull(requirements, "requirements must not be null");
        try {
            Setting synchronousCommit = setting(
                verificationConnection,
                "SHOW synchronous_commit"
            );
            Setting fsync = setting(verificationConnection, "SHOW fsync");
            Map<Table, RelationStatus> relations = new LinkedHashMap<>();
            for (Table table : requirements.tables()) {
                relations.put(table, relationStatus(verificationConnection, table));
            }
            return new PostgresqlDurabilityResult(
                synchronousCommit,
                fsync,
                relations
            );
        } catch (SQLException failure) {
            throw new IllegalStateException(
                "PostgreSQL durability verification could not complete"
            );
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

    private static RelationStatus relationStatus(Connection connection, Table table)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT c.relkind, c.relpersistence
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE (
                    n.nspname = ?
                    OR (? = 'pg_temp' AND n.oid = pg_my_temp_schema())
                  )
              AND c.relname = ?
            """)) {
            statement.setString(1, table.schema());
            statement.setString(2, table.schema());
            statement.setString(3, table.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return RelationStatus.MISSING;
                }
                String kind = result.getString("relkind");
                if (!"r".equals(kind)) {
                    return switch (kind) {
                        case "v" -> RelationStatus.VIEW;
                        case "m" -> RelationStatus.MATERIALIZED_VIEW;
                        case "f" -> RelationStatus.FOREIGN_TABLE;
                        case "S" -> RelationStatus.SEQUENCE;
                        case "p" -> RelationStatus.PARTITIONED_TABLE;
                        default -> RelationStatus.OTHER_RELATION_KIND;
                    };
                }
                return switch (result.getString("relpersistence")) {
                    case "p" -> RelationStatus.PERMANENT_TABLE;
                    case "u" -> RelationStatus.UNLOGGED_TABLE;
                    case "t" -> RelationStatus.TEMPORARY_TABLE;
                    default -> RelationStatus.OTHER_RELATION_KIND;
                };
            }
        }
    }
}
