package io.github.jacekkardys.systemproof.engine.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/** Attaches component runtime endpoint bindings to environment-owned connections. */
final class RuntimeBindings {
    private final RuntimeConnectionRegistry connections;

    RuntimeBindings(RuntimeConnectionRegistry connections) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
    }

    <C extends io.github.jacekkardys.systemproof.model.component.RuntimeConfig, O> void attach(
        AbstractComponent<C, O> component,
        ComponentRuntime<O> runtime
    ) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        List<ProvidedPort<?>> providedPorts = new ArrayList<>();
        component.ports().stream()
            .filter(ProvidedPort.class::isInstance)
            .map(port -> (ProvidedPort<?>) port)
            .forEach(providedPorts::add);
        for (ProvidedPort<?> port : providedPorts) {
            if (!runtime.materializes(port)) {
                IllegalStateException failure = new IllegalStateException(
                    "Driver for component '" + component.id() + "' did not materialize port '"
                        + port.qualifiedName() + "'"
                );
                connections.failProvidedPortMaterialization(port, failure);
                throw failure;
            }
        }

        List<RuntimeConnection.PreparedTargets<?>> prepared;
        try {
            prepared = connections.prepareTargets(component, runtime);
            connections.bindTargets(prepared);
        } catch (RuntimeException | Error failure) {
            connections.failProviderMaterialization(component, failure);
            throw failure;
        }
    }

    <T> T resolve(RequiredPort<T> required) {
        return connections.resolve(required);
    }

    Throwable beginDetach(AbstractComponent<?, ?> component) {
        return connections.beginProviderCleanup(component);
    }

    void completeDetach(AbstractComponent<?, ?> component) {
        connections.completeProviderCleanup(component);
    }

    void failDetach(AbstractComponent<?, ?> component, Throwable failure) {
        connections.failProviderCleanup(component, failure);
    }

    void providerStartFailure(AbstractComponent<?, ?> component, Throwable failure) {
        connections.failProviderMaterialization(component, failure);
    }
}
