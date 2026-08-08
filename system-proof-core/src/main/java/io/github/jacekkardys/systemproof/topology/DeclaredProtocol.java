package io.github.jacekkardys.systemproof.topology;

import java.util.Objects;

/** Protocol semantics with bounded ASCII metadata captured from a component port. */
public record DeclaredProtocol(String id, String scheme) implements ProtocolSpec {
    public DeclaredProtocol {
        id = requireIdentifier(id);
        Objects.requireNonNull(scheme, "protocol scheme must not be null");
        if (scheme.length() > 64
            || !scheme.matches("[a-zA-Z][a-zA-Z0-9+.-]*(?::[a-zA-Z0-9][a-zA-Z0-9+.-]*)*")) {
            throw new IllegalArgumentException(
                "protocol scheme must be 1-64 ASCII scheme characters"
            );
        }
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "protocol id must not be null");
        if (value.length() > 128 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(
                "protocol id must be 1-128 ASCII identifier characters"
            );
        }
        return value;
    }
}
