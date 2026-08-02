package io.github.jacekkardys.systemproof.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

/** Public construction API for validated environment runtime facades. */
public final class EnvironmentBuilder {
    private final List<AbstractComponent<?, ?>> components = new ArrayList<>();
    private final List<ConnectionRef> connections = new ArrayList<>();
    private final ComponentFactory componentFactory;
    private EnvironmentLogging logging = EnvironmentLogging.defaults();

    public EnvironmentBuilder() {
        this(EnvironmentConfiguration.system());
    }

    public EnvironmentBuilder(EnvironmentConfiguration configuration) {
        componentFactory = ComponentFactory.from(configuration);
    }

    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(Class<T> type) {
        return register(componentFactory.create(type));
    }

    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(String qualifier, Class<T> type) {
        return register(componentFactory.create(type, qualifier));
    }

    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(Class<T> type, C configuration,
        ComponentDriver<C, O> driver) {
        return register(componentFactory.create(type, configuration, driver));
    }

    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(String qualifier, Class<T> type,
        C configuration, ComponentDriver<C, O> driver) {
        return register(componentFactory.create(qualifier, type, configuration, driver));
    }

    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(Class<T> type,
        ComponentType componentType, String qualifier, C configuration, ComponentDriver<C, O> driver) {
        return register(componentFactory.create(type, componentType, qualifier, configuration, driver));
    }

    public EnvironmentBuilder components(AbstractComponent<?, ?>... values) {
        components.addAll(List.of(Objects.requireNonNull(values, "components must not be null")));
        return this;
    }

    public <C> EnvironmentBuilder connect(RequiredPort<C> from, ProvidedPort<C> to) {
        connections.add(Connection.connect(from, to));
        return this;
    }

    public EnvironmentBuilder logging(EnvironmentLogging.Builder builder) {
        logging = Objects.requireNonNull(builder, "logging must not be null").build();
        return this;
    }

    public EnvironmentBuilder logging(EnvironmentLogging configuration) {
        logging = Objects.requireNonNull(configuration, "logging must not be null");
        return this;
    }

    public Environment build() {
        return build(DefaultEnvironment::new);
    }

    public <E extends Environment> E build(EnvironmentCreator<E> creator) {
        Objects.requireNonNull(creator, "creator must not be null");
        var topology = new EnvironmentTopology(components, connections);
        logging.validateAgainst(topology);
        return Objects.requireNonNull(creator.create(topology, logging), "creator must not return null");
    }

    private <T extends AbstractComponent<?, ?>> T register(T component) {
        components.add(component);
        return component;
    }

    /** Creates a concrete runtime facade from validated immutable construction results. */
    @FunctionalInterface
    public interface EnvironmentCreator<E extends Environment> {
        E create(EnvironmentTopology topology, EnvironmentLogging logging);
    }

    private static final class DefaultEnvironment extends Environment {
        private DefaultEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }
    }
}
