package io.github.jacekkardys.systemproof.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.engine.EnvironmentRuntime;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;

/** Small public facade over an immutable topology and one internal runtime execution. */
public class Environment implements AutoCloseable {
    private final EnvironmentTopology topology;
    private final EnvironmentLogging logging;
    private final EnvironmentRuntime runtime;

    protected Environment(Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null");
        topology = new EnvironmentTopology(builder.components, builder.connections);
        logging = builder.logging;
        logging.validateAgainst(this);
        runtime = new EnvironmentRuntime(topology, logging);
    }

    public static Builder environment() {
        return new Builder();
    }

    public final List<Component> components() {
        return topology.components();
    }

    public final List<ConnectionRef> connections() {
        return topology.connections();
    }

    public final EnvironmentLogging logging() {
        return logging;
    }

    public final EnvironmentState state() {
        return runtime.state();
    }

    public final boolean contains(Component component) {
        return topology.contains(component);
    }

    public final ConnectionRef connectionFrom(RequiredPort<?> port) {
        return topology.connectionFrom(port);
    }

    public final Environment start() {
        runtime.start();
        return this;
    }

    public final boolean isRunning() {
        return state() == EnvironmentState.RUNNING;
    }

    public final EnvironmentDiagnostics diagnostics() {
        return runtime.diagnostics();
    }

    /** Captures a detached immutable snapshot of the scenario's structured journal. */
    public final ScenarioJournalSnapshot journalSnapshot() {
        return runtime.journalSnapshot();
    }

    protected final <C extends RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        if (!contains(component)) {
            throw new IllegalArgumentException(
                "Component '" + component.id() + "' is outside the environment"
            );
        }
        return runtime.operations(component);
    }

    public final ComponentState componentState(Component component) {
        return runtime.componentState(component);
    }

    @Override
    public final void close() {
        runtime.close();
    }

    public static class Builder {
        private final List<AbstractComponent<?, ?>> components = new ArrayList<>();
        private final List<ConnectionRef> connections = new ArrayList<>();
        private EnvironmentLogging logging = EnvironmentLogging.defaults();

        protected Builder() {}

        public Builder components(AbstractComponent<?, ?>... values) {
            components.addAll(List.of(Objects.requireNonNull(values, "components must not be null")));
            return this;
        }

        public <C> Builder connect(RequiredPort<C> from, ProvidedPort<C> to) {
            connections.add(Connection.connect(from, to));
            return this;
        }

        public Builder logging(EnvironmentLogging.Builder builder) {
            logging = Objects.requireNonNull(builder, "logging must not be null").build();
            return this;
        }

        public Builder logging(EnvironmentLogging configuration) {
            logging = Objects.requireNonNull(configuration, "logging must not be null");
            return this;
        }

        public Environment build() {
            return new Environment(this);
        }
    }
}
