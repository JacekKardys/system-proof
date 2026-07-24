package io.github.jacekkardys.systemproof.model;

import java.util.Locale;
import java.util.Objects;

/** Stable logical component kind, independent of a concrete instance. */
public record ComponentType(String value) {
    public ComponentType {
        Objects.requireNonNull(value, "component type must not be null");
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid component type: " + value);
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    public static ComponentType of(String value) {
        return new ComponentType(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
