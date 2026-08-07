package io.github.jacekkardys.systemproof.component;

import java.util.Locale;
import java.util.Objects;

/** Stable 1-64 character ASCII logical component kind, independent of an instance. */
public record ComponentType(String value) {
    public ComponentType {
        Objects.requireNonNull(value, "component type must not be null");
        if (value.length() > 64 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                "component type must be 1-64 ASCII identifier characters"
            );
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
