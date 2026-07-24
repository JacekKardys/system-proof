package pl.gov.il.test.harness.configuration;

import pl.gov.il.test.harness.model.EnvironmentConfiguration;

/** Resolves the textual value declared by a configuration source annotation. */
@FunctionalInterface
public interface ConfigurationProvider {
    String resolve(ConfigurationSource source, EnvironmentConfiguration environment);
}
