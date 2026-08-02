package io.github.jacekkardys.systemproof.configuration;

import static io.github.jacekkardys.systemproof.configuration.ConfigurationValues.requireText;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, redacted snapshot of external values used during environment construction. */
public final class EnvironmentConfiguration {
    private final Map<String, String> values;

    private EnvironmentConfiguration(Map<String, String> values) {
        Objects.requireNonNull(values, "values must not be null");
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
            requireText(key, "configuration key"),
            Objects.requireNonNull(value, "configuration value for '" + key + "' must not be null")
        ));
        this.values = Map.copyOf(copy);
    }

    /** Creates a detached configuration snapshot from explicit values. */
    public static EnvironmentConfiguration of(Map<String, String> values) {
        return new EnvironmentConfiguration(values);
    }

    /** Captures the current process environment variables and system properties. */
    public static EnvironmentConfiguration system() {
        Map<String, String> values = new LinkedHashMap<>(System.getenv());
        System.getProperties().forEach((key, value) -> values.put(key.toString(), value.toString()));
        return of(values);
    }

    /** Returns the required non-blank value or fails when it is absent. */
    public String required(String key) {
        String normalized = requireText(key, "configuration key");
        return optional(normalized)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalStateException(
                "Required environment configuration value '" + normalized + "' is missing"
            ));
    }

    /** Returns the configured value without interpreting blank text. */
    public Optional<String> optional(String key) {
        return Optional.ofNullable(values.get(requireText(key, "configuration key")));
    }

    /** Returns a non-blank configured value or the supplied default. */
    public String value(String key, String defaultValue) {
        return optional(key).filter(value -> !value.isBlank()).orElseGet(() ->
            requireText(defaultValue, "default value for '" + key + "'")
        );
    }

    /** Resolves an integer value with an explicit default. */
    public int integer(String key, int defaultValue) {
        String configured = value(key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Configuration value '" + key + "' must be an integer but was '" + configured + "'",
                exception
            );
        }
    }

    /** Resolves an ISO-8601 duration with an explicit default. */
    public Duration duration(String key, Duration defaultValue) {
        String configured = value(key, Objects.requireNonNull(defaultValue, "defaultValue must not be null").toString());
        try {
            return Duration.parse(configured);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                "Configuration value '" + key + "' must be an ISO-8601 duration but was '" + configured + "'",
                exception
            );
        }
    }

    /** Binds and validates one typed component or driver configuration contract. */
    public <T> T bind(Class<T> configurationType) {
        return ConfigurationBinder.bind(configurationType, this);
    }

    @Override
    public String toString() {
        return "EnvironmentConfiguration[redacted]";
    }
}
