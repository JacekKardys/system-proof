package io.github.jacekkardys.systemproof.configuration;

import static io.github.jacekkardys.systemproof.configuration.ConfigurationSource.UNSET;
import static io.github.jacekkardys.systemproof.configuration.ConfigurationValues.requireText;

import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;

/** Resolves a value from the external environment configuration view. */
public final class EnvironmentVariable implements ConfigurationProvider {
    @Override
    public String resolve(ConfigurationSource source, EnvironmentConfiguration environment) {
        String key = requireText(setting(source.key(), "key"), "environment configuration key");
        if (!UNSET.equals(source.value())) {
            throw new IllegalArgumentException(
                "EnvironmentVariable configuration source must not declare a literal value"
            );
        }
        return UNSET.equals(source.defaultValue())
            ? environment.required(key)
            : environment.value(key, source.defaultValue());
    }

    private static String setting(String value, String name) {
        if (UNSET.equals(value)) {
            throw new IllegalArgumentException(
                "EnvironmentVariable configuration source must declare " + name
            );
        }
        return value;
    }
}
