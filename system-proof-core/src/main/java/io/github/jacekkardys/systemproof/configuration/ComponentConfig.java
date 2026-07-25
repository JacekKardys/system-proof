package io.github.jacekkardys.systemproof.configuration;

import io.github.jacekkardys.systemproof.model.DriverConfig;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

/** Typed component configuration associated with one driver-only configuration. */
public interface ComponentConfig<D extends DriverConfig> extends RuntimeConfig {}
