package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentLoggingBuilder;
import io.github.jacekkardys.systemproof.configuration.EnvironmentConfiguration;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;

/**
 * Mutable construction boundary for components, connections, logging, and validated environment facades.
 * Builder state is never retained by the resulting environment.
 */
public final class EnvironmentBuilder {
    private final List<AbstractComponent<?, ?>> components = new ArrayList<>();
    private final List<ConnectionRef> connections = new ArrayList<>();
    private final ComponentFactory componentFactory;
    private EnvironmentLogging logging = EnvironmentLogging.defaults();

    /** Creates a builder using a snapshot of system properties and environment variables. */
    public EnvironmentBuilder() {
        this(EnvironmentConfiguration.system());
    }

    /**
     * Creates a builder using an explicit immutable configuration snapshot.
     *
     * @param configuration values used to materialize declarative components and drivers
     */
    public EnvironmentBuilder(EnvironmentConfiguration configuration) {
        componentFactory = ComponentFactory.from(configuration);
    }

    /** Materializes and registers an unqualified declarative component. */
    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(Class<T> type) {
        return register(componentFactory.create(type));
    }

    /** Materializes and registers a qualified declarative component. */
    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(String qualifier, Class<T> type) {
        return register(componentFactory.create(type, qualifier));
    }

    /** Registers a component materialized from an explicit configuration and driver. */
    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(Class<T> type, C configuration,
        ComponentDriver<C, O> driver) {
        return register(componentFactory.create(type, configuration, driver));
    }

    /** Registers a qualified component materialized from an explicit configuration and driver. */
    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(String qualifier, Class<T> type,
        C configuration, ComponentDriver<C, O> driver) {
        return register(componentFactory.create(qualifier, type, configuration, driver));
    }

    /** Registers a component with an explicit type, qualifier, configuration, and driver. */
    public <C extends RuntimeConfig, O, T extends AbstractComponent<C, O>> T component(Class<T> type,
        ComponentType componentType, String qualifier, C configuration, ComponentDriver<C, O> driver) {
        return register(componentFactory.create(type, componentType, qualifier, configuration, driver));
    }

    /** Registers already materialized component declarations. */
    public EnvironmentBuilder components(AbstractComponent<?, ?>... values) {
        components.addAll(List.of(Objects.requireNonNull(values, "components must not be null")));
        return this;
    }

    /** Declares one typed required-to-provided connection. */
    public <C> EnvironmentBuilder connect(RequiredPort<C> from, ProvidedPort<C> to) {
        connections.add(ConnectionFactory.create(from, to));
        return this;
    }

    /** Uses the logging configuration produced by the supplied logging builder. */
    public EnvironmentBuilder logging(EnvironmentLoggingBuilder builder) {
        logging = Objects.requireNonNull(builder, "logging must not be null").build();
        return this;
    }

    /** Uses an immutable logging configuration. */
    public EnvironmentBuilder logging(EnvironmentLogging configuration) {
        logging = Objects.requireNonNull(configuration, "logging must not be null");
        return this;
    }

    /** Builds the default environment facade. */
    public Environment build() {
        return build(Environment::new);
    }

    /**
     * Validates and freezes construction state before creating a typed environment facade.
     *
     * @param creator facade creator invoked with immutable construction results
     * @return the created environment facade
     */
    public <E extends Environment> E build(EnvironmentCreator<E> creator) {
        Objects.requireNonNull(creator, "creator must not be null");
        TopologyValidator.validate(components, connections);
        EnvironmentTopology topology = EnvironmentTopology.of(components, connections);
        logging.validateAgainst(topology);
        return Objects.requireNonNull(creator.create(topology, logging), "creator must not return null");
    }

    private <T extends AbstractComponent<?, ?>> T register(T component) {
        components.add(component);
        return component;
    }

}
