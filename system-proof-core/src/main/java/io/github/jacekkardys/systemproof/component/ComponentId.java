package io.github.jacekkardys.systemproof.component;

import java.util.Objects;
import java.util.Optional;

/** Stable component type plus an optional 1-64 character ASCII instance qualifier. */
public record ComponentId(ComponentType type, Optional<String> qualifier) {
    public ComponentId {
        type = Objects.requireNonNull(type, "type must not be null");
        qualifier = Objects.requireNonNull(qualifier, "qualifier must not be null")
            .map(value -> requireIdentifier(value, "component qualifier"));
    }

    public static ComponentId component(ComponentType type) {
        return new ComponentId(type, Optional.empty());
    }

    public static ComponentId component(ComponentType type, String qualifier) {
        return new ComponentId(type, Optional.ofNullable(qualifier));
    }

    public String value() {
        return qualifier.map(value -> type.value() + "-" + value).orElse(type.value());
    }

    @Override
    public String toString() {
        return value();
    }

    private static String requireIdentifier(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.length() > 64 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                description + " must be 1-64 ASCII identifier characters"
            );
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
