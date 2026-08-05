package io.github.jacekkardys.systemproof.postgresql;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Permanent tables whose server-side durability facts must be verified before proof traffic.
 *
 * @param tables non-empty set of required schema-qualified tables
 */
public record PostgresqlDurabilityRequirements(Set<Table> tables) {
    public PostgresqlDurabilityRequirements {
        Objects.requireNonNull(tables, "tables must not be null");
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("tables must not be empty");
        }
        if (tables.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("tables must not contain null");
        }
        tables = Set.copyOf(tables);
    }

    /**
     * Canonical unquoted PostgreSQL table identity used only as prepared-query parameters.
     *
     * @param schema schema identifier
     * @param name table identifier
     */
    public record Table(String schema, String name) {
        public Table {
            schema = identifier(schema, "schema");
            name = identifier(name, "table");
        }

        private static String identifier(String value, String description) {
            Objects.requireNonNull(value, description + " must not be null");
            if (!value.matches("[a-zA-Z_][a-zA-Z0-9_$]*")) {
                throw new IllegalArgumentException(
                    "Invalid PostgreSQL " + description + " identifier"
                );
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }
}
