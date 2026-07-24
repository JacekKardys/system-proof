package io.github.jacekkardys.systemproof.configuration;

import java.util.Objects;

/** Stateless validation helpers for environment configuration values. */
public final class ConfigurationValues {
    private ConfigurationValues() {}

    public static <T> T requireNonNull(T value, String description) {
        return Objects.requireNonNull(value, description + " must not be null");
    }

    public static String requireText(String value, String description) {
        requireNonNull(value, description);
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
