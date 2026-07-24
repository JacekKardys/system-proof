package pl.gov.il.test.harness.configuration;

import pl.gov.il.test.harness.model.DriverConfig;
import pl.gov.il.test.harness.model.RuntimeConfig;

/** Associates one component runtime configuration with its driver configuration. */
public interface ComponentConfig<
    R extends RuntimeConfig,
    D extends DriverConfig
> {}
