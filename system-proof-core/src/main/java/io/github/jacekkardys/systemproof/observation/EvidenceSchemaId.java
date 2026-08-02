package io.github.jacekkardys.systemproof.observation;

import java.util.Locale;
import java.util.Objects;

/** Stable identity and version of one module-owned evidence schema. */
public record EvidenceSchemaId(String namespace, String name, int version) {
    public EvidenceSchemaId {
        namespace = requireIdentifier(namespace, "evidence namespace");
        name = requireIdentifier(name, "evidence name");
        if (version < 1) {
            throw new IllegalArgumentException("evidence version must be at least 1");
        }
    }

    private static String requireIdentifier(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
