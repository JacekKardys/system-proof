package io.github.jacekkardys.systemproof.observation;

import java.util.Locale;
import java.util.Objects;

/** Stable bounded ASCII identity and version of one module-owned evidence schema. */
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
        if (value.length() > 128 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(
                description + " must be 1-128 ASCII identifier characters"
            );
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
