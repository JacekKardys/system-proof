package io.github.jacekkardys.systemproof.construction;

import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

/** Compile-time access bridge for completing declarative component construction. */
final class ComponentInitializer extends AbstractComponent<RuntimeConfig, Void> {
    private ComponentInitializer() {}

    static <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T initialize(T component, ComponentId id,
        C configuration, Class<O> operationsType, ComponentDriver<C, O> driver) {
        return completeConstruction(component, id, configuration, operationsType, driver);
    }
}
