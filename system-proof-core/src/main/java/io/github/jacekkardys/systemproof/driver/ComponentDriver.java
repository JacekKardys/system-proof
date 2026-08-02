package io.github.jacekkardys.systemproof.driver;

import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;

/** Materializes one component instance in a selected runtime technology. */
@FunctionalInterface
public interface ComponentDriver<C extends RuntimeConfig, O> {
    ComponentRuntime<O> start(AbstractComponent<C, O> component, DriverContext context);
}
