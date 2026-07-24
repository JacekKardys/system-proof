package io.github.jacekkardys.systemproof.configuration;

import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;

/** Resolves the textual value declared by a configuration source annotation. */
@FunctionalInterface
public interface ConfigurationProvider {
    String resolve(ConfigurationSource source, EnvironmentConfiguration environment);
}
