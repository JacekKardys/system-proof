package io.github.jacekkardys.systemproof.engine;

import java.util.Locale;
import java.util.Objects;

/** Stable namespaced identity and version of one domain-defined correlation-key schema. */
public record CorrelationKeySchema(String namespace, String name, int version) {
    public CorrelationKeySchema {
        namespace = requireIdentifier(namespace, "correlation-key namespace");
        name = requireIdentifier(name, "correlation-key name");
        if (version < 1) {
            throw new IllegalArgumentException(
                "correlation-key version must be at least 1"
            );
        }
    }

    @Override
    public String toString() {
        return namespace + ":" + name + ":v" + version;
    }

    private static String requireIdentifier(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
