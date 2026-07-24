package io.github.jacekkardys.systemproof.configuration;

import static io.github.jacekkardys.systemproof.configuration.ConfigurationSource.UNSET;

import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;

/** Resolves a literal value declared directly on a configuration method. */
public final class Literal implements ConfigurationProvider {
    @Override
    public String resolve(ConfigurationSource source, EnvironmentConfiguration environment) {
        if (!UNSET.equals(source.key()) || !UNSET.equals(source.defaultValue())) {
            throw new IllegalArgumentException(
                "Literal configuration source must declare only value"
            );
        }
        if (UNSET.equals(source.value())) {
            throw new IllegalArgumentException(
                "Literal configuration source must declare value"
            );
        }
        return source.value();
    }
}
