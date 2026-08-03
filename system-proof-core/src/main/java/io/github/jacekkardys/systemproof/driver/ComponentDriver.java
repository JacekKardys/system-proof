package io.github.jacekkardys.systemproof.driver;

import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;

/** Materializes one component instance in a selected runtime technology. */
@FunctionalInterface
public interface ComponentDriver<C extends RuntimeConfig, O> {
    ComponentRuntime<O> start(AbstractComponent<C, O> component, DriverContext context);
}
