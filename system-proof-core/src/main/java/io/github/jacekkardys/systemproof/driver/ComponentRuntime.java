package io.github.jacekkardys.systemproof.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.engine.RuntimeEndpointBindings;
import io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;

/** Driver result owned by the environment runtime for one running component. */
public final class ComponentRuntime<O> implements AutoCloseable {
    private final AutoCloseable resource;
    private final List<PublishedEndpoint<?>> endpoints;
    private final O operations;
    private final List<DiagnosticSource> diagnostics;

    private ComponentRuntime(Builder<O> builder) {
        resource = builder.resource;
        endpoints = List.copyOf(builder.endpoints);
        operations = builder.operations;
        diagnostics = List.copyOf(builder.diagnostics);
    }

    public static <O> Builder<O> runtime(AutoCloseable resource) {
        return new Builder<>(resource);
    }

    public static <O> Builder<O> runtime() {
        return new Builder<>(() -> {});
    }

    /**
     * Transfers published bindings into an engine-owned access boundary.
     *
     * <p>The boundary cannot be constructed outside the runtime engine and exposes no public
     * endpoint lookup. Component drivers can publish endpoints but cannot retrieve them here.
     */
    public void publishBindingsTo(RuntimeEndpointBindings target) {
        Objects.requireNonNull(target, "target must not be null");
        endpoints.forEach(endpoint -> endpoint.publishTo(target));
    }

    public boolean materializes(ProvidedPort<?> port) {
        return endpoints.stream().anyMatch(endpoint -> endpoint.port() == port);
    }

    public O operations() {
        return operations;
    }

    public List<DiagnosticSource> diagnostics() {
        return diagnostics;
    }

    @Override
    public void close() throws Exception {
        resource.close();
    }

    public static final class Builder<O> {
        private final AutoCloseable resource;
        private final List<PublishedEndpoint<?>> endpoints = new ArrayList<>();
        private final List<DiagnosticSource> diagnostics = new ArrayList<>();
        private O operations;

        private Builder(AutoCloseable resource) {
            this.resource = Objects.requireNonNull(resource, "resource must not be null");
        }

        public <T> Builder<O> provides(ProvidedPort<T> port, EndpointBinding<T> binding) {
            Objects.requireNonNull(port, "port must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            port.contract().cast(binding.internal());
            port.contract().cast(binding.external());
            if (endpoints.stream().anyMatch(endpoint -> endpoint.port() == port)) {
                throw new IllegalArgumentException(
                    "Port '" + port.qualifiedName() + "' was materialized more than once"
                );
            }
            endpoints.add(new PublishedEndpoint<>(port, binding));
            return this;
        }

        public Builder<O> operations(O value) {
            operations = Objects.requireNonNull(value, "operations must not be null");
            return this;
        }

        public Builder<O> diagnostics(DiagnosticSource source) {
            diagnostics.add(Objects.requireNonNull(source, "source must not be null"));
            return this;
        }

        public ComponentRuntime<O> build() {
            return new ComponentRuntime<>(this);
        }
    }

    private record PublishedEndpoint<T>(
        ProvidedPort<T> port,
        EndpointBinding<T> binding
    ) {
        private void publishTo(RuntimeEndpointBindings target) {
            target.publish(port, binding);
        }
    }
}
