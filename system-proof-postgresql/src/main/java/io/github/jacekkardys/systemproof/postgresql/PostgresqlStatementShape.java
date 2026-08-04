package io.github.jacekkardys.systemproof.postgresql;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured supported write shape without retaining or rendering SQL text.
 *
 * @param kind supported statement kind
 * @param schema normalized optional schema identifier
 * @param table normalized table identifier
 * @param columns normalized ordered column identifiers
 */
public record PostgresqlStatementShape(
    Kind kind,
    Optional<String> schema,
    String table,
    List<String> columns
) {
    public enum Kind {
        /** A single-row parameterized insert in the characterized subset. */
        INSERT
    }

    public PostgresqlStatementShape {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        schema = Objects.requireNonNull(schema, "schema must not be null")
            .map(value -> identifier(value, "schema"));
        table = identifier(table, "table");
        Objects.requireNonNull(columns, "columns must not be null");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        columns = columns.stream().map(value -> identifier(value, "column")).toList();
        if (columns.stream().distinct().count() != columns.size()) {
            throw new IllegalArgumentException("columns must not contain duplicates");
        }
    }

    @Override
    public String toString() {
        return "PostgresqlStatementShape[kind=" + kind
            + ", schemaQualified=" + schema.isPresent()
            + ", columnCount=" + columns.size() + "]";
    }

    private static String identifier(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (!value.matches("[a-zA-Z_][a-zA-Z0-9_$]*")) {
            throw new IllegalArgumentException("Invalid PostgreSQL " + description + " identifier");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
