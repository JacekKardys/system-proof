package io.github.jacekkardys.systemproof.engine;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Running component handles and typed values published for provided endpoint contracts. */
final class RuntimeBindings {
    private final Function<RequiredPort<?>, ConnectionRef> connectionFrom;
    private final Map<Component, ComponentRuntime<?>> runtimes =
        new IdentityHashMap<>();

    RuntimeBindings(Function<RequiredPort<?>, ConnectionRef> connectionFrom) {
        this.connectionFrom = Objects.requireNonNull(connectionFrom, "connectionFrom must not be null");
    }

    <C extends io.github.jacekkardys.systemproof.model.RuntimeConfig, O> void attach(
        AbstractComponent<C, O> component,
        ComponentRuntime<O> runtime
    ) {
        component.ports().stream()
            .filter(ProvidedPort.class::isInstance)
            .map(ProvidedPort.class::cast)
            .filter(port -> !runtime.materializes(port))
            .findFirst()
            .ifPresent(port -> {
                throw new IllegalStateException(
                    "Driver for component '" + component.id() + "' did not materialize port '"
                        + port.qualifiedName() + "'"
                );
            });
        if (runtimes.put(component, runtime) != null) {
            throw new IllegalStateException("Component '" + component.id() + "' already has a runtime");
        }
    }

    <T> T resolve(RequiredPort<T> required) {
        ConnectionRef connection = connectionFrom.apply(required);
        ProvidedPort<?> provided = (ProvidedPort<?>) connection.to();
        ComponentRuntime<?> runtime = requireRuntime(provided.owner());
        Object value = runtime.resolve(provided);
        return required.contract().cast(value);
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

    void detach(AbstractComponent<?, ?> component) {
        runtimes.remove(component);
    }

    private ComponentRuntime<?> requireRuntime(Component component) {
        ComponentRuntime<?> runtime = runtimes.get(component);
        if (runtime == null) {
            throw new IllegalStateException("Component '" + component.id() + "' has no runtime");
        }
        return runtime;
    }
}
