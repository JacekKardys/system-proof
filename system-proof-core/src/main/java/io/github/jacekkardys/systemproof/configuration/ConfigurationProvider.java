package io.github.jacekkardys.systemproof.configuration;


/** Resolves the textual value declared by a configuration source annotation. */
@FunctionalInterface
public interface ConfigurationProvider {
    String resolve(ConfigurationSource source, EnvironmentConfiguration environment);
}
