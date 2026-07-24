package io.github.jacekkardys.systemproof.configuration;

import io.github.jacekkardys.systemproof.model.DriverConfig;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

/** Associates one component runtime configuration with its driver configuration. */
public interface ComponentConfig<
    R extends RuntimeConfig,
    D extends DriverConfig
> {}
