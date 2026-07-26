package io.github.jacekkardys.systemproof.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Running component handles and typed values published for provided endpoint contracts. */
final class RuntimeBindings {
    private final RuntimeConnectionRegistry connections;
    private final Map<Component, ComponentRuntime<?>> runtimes =
        new IdentityHashMap<>();

    RuntimeBindings(RuntimeConnectionRegistry connections) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
    }

    <C extends io.github.jacekkardys.systemproof.model.RuntimeConfig, O> void attach(
        AbstractComponent<C, O> component,
        ComponentRuntime<O> runtime
    ) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        if (runtimes.containsKey(component)) {
            throw new IllegalStateException("Component '" + component.id() + "' already has a runtime");
        }
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
        runtimes.put(component, runtime);
    }

    <T> T resolve(RequiredPort<T> required) {
        return connections.resolve(required);
    }

    <C extends io.github.jacekkardys.systemproof.model.RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        Object operations = requireRuntime(component).operations();
        if (operations == null) {
            throw new IllegalStateException(
                "Component '" + component.id() + "' (type=" + component.type()
                    + ") has no runtime operations"
            );
        }
        return component.castOperations(operations);
    }

    ComponentRuntime<?> runtime(AbstractComponent<?, ?> component) {
        return requireRuntime(component);
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

    void detachRuntime(AbstractComponent<?, ?> component) {
        runtimes.remove(component);
    }

    void providerStartFailure(AbstractComponent<?, ?> component, Throwable failure) {
        connections.failProviderMaterialization(component, failure);
    }

    private ComponentRuntime<?> requireRuntime(Component component) {
        ComponentRuntime<?> runtime = runtimes.get(component);
        if (runtime == null) {
            throw new IllegalStateException("Component '" + component.id() + "' has no runtime");
        }
        return runtime;
    }
}
