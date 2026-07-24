package pl.gov.il.test.harness.configuration;

import static pl.gov.il.test.harness.configuration.ConfigurationSource.UNSET;

import pl.gov.il.test.harness.model.EnvironmentConfiguration;

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
