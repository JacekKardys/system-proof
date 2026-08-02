package io.github.jacekkardys.systemproof.construction;

import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

/** Materializes component declarations for one immutable environment-configuration snapshot. */
final class ComponentFactory {
    private final EnvironmentConfiguration values;

    private ComponentFactory(EnvironmentConfiguration values) {
        this.values = Objects.requireNonNull(values, "values must not be null");
    }

    static ComponentFactory from(EnvironmentConfiguration values) {
        return new ComponentFactory(values);
    }

    <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T create(Class<T> type) {
        return create(type, null);
    }

    <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T create(Class<T> type, String qualifier) {
        return ComponentMetadata.<C, O, T>analyze(type).materialize(qualifier, values);
    }

    <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T create(Class<T> type, C configuration,
        ComponentDriver<C, O> driver) {
        return create(null, type, configuration, driver);
    }

    <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T create(String qualifier, Class<T> type,
        C configuration, ComponentDriver<C, O> driver) {
        return ComponentMetadata.<C, O, T>analyze(type).materialize(qualifier, configuration, driver);
    }

    <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T create(Class<T> type,
        ComponentType componentType, String qualifier, C configuration, ComponentDriver<C, O> driver) {
        return ComponentMetadata.materialize(type, componentType, qualifier, configuration, driver);
    }
}
