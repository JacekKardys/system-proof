package pl.gov.il.test.harness.model;

import static pl.gov.il.test.harness.configuration.ConfigurationValues.requireText;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import pl.gov.il.test.harness.configuration.ConfigurationBinder;

/** Immutable external configuration snapshot used to bind annotated component configuration. */
public interface EnvironmentConfiguration {
    String required(String key);

    Optional<String> optional(String key);

    default String value(String key, String defaultValue) {
        return optional(key).filter(value -> !value.isBlank()).orElseGet(() ->
            requireText(defaultValue, "default value for '" + key + "'")
        );
    }

    default int integer(String key, int defaultValue) {
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

    default Duration duration(String key, Duration defaultValue) {
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

    default <T> T bind(Class<T> configurationType) {
        return ConfigurationBinder.bind(configurationType, this);
    }

    static EnvironmentConfiguration of(Map<String, String> values) {
        return new Values(values);
    }

    static EnvironmentConfiguration system() {
        Map<String, String> values = new LinkedHashMap<>(System.getenv());
        System.getProperties().forEach((key, value) -> values.put(key.toString(), value.toString()));
        return of(values);
    }

    final class Values implements EnvironmentConfiguration {
        private final Map<String, String> values;

        private Values(Map<String, String> values) {
            Objects.requireNonNull(values, "values must not be null");
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(
                requireText(key, "configuration key"),
                Objects.requireNonNull(value, "configuration value for '" + key + "' must not be null")
            ));
            this.values = Map.copyOf(copy);
        }

        @Override
        public String required(String key) {
            String normalized = requireText(key, "configuration key");
            return optional(normalized)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                    "Required environment configuration value '" + normalized + "' is missing"
                ));
        }

        @Override
        public Optional<String> optional(String key) {
            return Optional.ofNullable(values.get(requireText(key, "configuration key")));
        }

        @Override
        public String toString() {
            return "EnvironmentConfiguration[redacted]";
        }
    }
}
