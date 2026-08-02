package io.github.jacekkardys.systemproof.engine;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;

/**
 * Engine-owned typed access to endpoints published by one component runtime.
 *
 * <p>The type is public only so {@code ComponentRuntime} can transfer bindings across the package
 * boundary. Its constructor and all endpoint lookup operations remain runtime-internal.
 */
public final class RuntimeEndpointBindings {
    private final Map<ProvidedPort<?>, EndpointBinding<?>> bindings = new IdentityHashMap<>();

    RuntimeEndpointBindings() {}

    /**
     * Accepts one typed binding from {@code ComponentRuntime}.
     *
     * <p>Callers outside the engine cannot construct the receiving boundary.
     */
    public <T> void publish(ProvidedPort<T> port, EndpointBinding<T> binding) {
        Objects.requireNonNull(port, "port must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        if (bindings.putIfAbsent(port, binding) != null) {
            throw new IllegalStateException(
                "Port '" + port.qualifiedName() + "' was published more than once"
            );
        }
    }

    <T> EndpointBinding<T> binding(ProvidedPort<T> port) {
        Objects.requireNonNull(port, "port must not be null");
        EndpointBinding<?> binding = bindings.get(port);
        if (binding == null) {
            throw new IllegalStateException(
                "Runtime did not materialize port '" + port.qualifiedName() + "'"
            );
        }
        return EndpointBinding.binding(
            port.contract().cast(binding.internal()),
            port.contract().cast(binding.external())
        );
    }
}
