package pl.gov.il.test.harness.testcontainers.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import pl.gov.il.test.harness.driver.ComponentRuntime;
import pl.gov.il.test.harness.model.Component;
import pl.gov.il.test.harness.model.EndpointAddress;
import pl.gov.il.test.harness.model.EndpointBinding;
import pl.gov.il.test.harness.model.PortDirection;
import pl.gov.il.test.harness.model.ProvidedPort;

/** A prepared container and typed publishers for its logical provided ports. */
public final class ContainerPlan {
    private final GenericContainer<?> container;
    private final List<DeclaredEndpoint> endpoints;

    private ContainerPlan(Builder builder) {
        container = builder.container;
        endpoints = List.copyOf(builder.endpoints);
    }

    public static Builder container(GenericContainer<?> container) {
        return new Builder(container);
    }

    public GenericContainer<?> container() {
        return container;
    }

    public List<ProvidedPort<?>> ports() {
        return endpoints.stream().map(DeclaredEndpoint::port).toList();
    }

    public Integer[] exposedPorts() {
        return endpoints.stream()
            .map(DeclaredEndpoint::containerPort)
            .distinct()
            .toArray(Integer[]::new);
    }

    public void validateFor(Component component) {
        component.ports().stream()
            .filter(port -> port.direction() == PortDirection.PROVIDED)
            .map(port -> (ProvidedPort<?>) port)
            .filter(port -> endpoints.stream().noneMatch(endpoint -> endpoint.port() == port))
            .findFirst()
            .ifPresent(port -> {
                throw new IllegalArgumentException(
                    "Driver for component '" + component.id() + "' did not declare a port for '"
                        + port.qualifiedName() + "'"
                );
            });
        endpoints.stream()
            .map(DeclaredEndpoint::port)
            .filter(port -> port.owner() != component)
            .findFirst()
            .ifPresent(port -> {
                throw new IllegalArgumentException(
                    "Driver for component '" + component.id() + "' declared foreign port '"
                        + port.qualifiedName() + "'"
                );
            });
    }

    <T> EndpointBinding<T> binding(ProvidedPort<T> port, StartedContainer container) {
        EndpointBinding<?> binding = endpoint(port).materialize(container);
        return EndpointBinding.binding(
            port.contract().cast(binding.internal()),
            port.contract().cast(binding.external())
        );
    }

    <O> void publishTo(ComponentRuntime.Builder<O> runtime, StartedContainer container) {
        endpoints.forEach(endpoint -> endpoint.publishTo(runtime, container));
    }

    int containerPort(ProvidedPort<?> port) {
        return endpoint(port).containerPort();
    }

    private DeclaredEndpoint endpoint(ProvidedPort<?> port) {
        return endpoints.stream()
            .filter(endpoint -> endpoint.port() == port)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Container plan has no binding for port '" + port.qualifiedName() + "'"
            ));
    }

    public static final class Builder {
        private final GenericContainer<?> container;
        private final List<DeclaredEndpoint> endpoints = new ArrayList<>();

        private Builder(GenericContainer<?> container) {
            this.container = Objects.requireNonNull(container, "container must not be null");
        }

        public Builder provides(ProvidedPort<EndpointAddress> port, PortBinding binding) {
            return provides(port, binding, "", address -> address);
        }

        public Builder provides(
            ProvidedPort<EndpointAddress> port,
            PortBinding binding,
            String path
        ) {
            return provides(port, binding, path, address -> address);
        }

        public <T> Builder provides(
            ProvidedPort<T> port,
            PortBinding binding,
            RuntimeEndpointFactory<T> endpointFactory
        ) {
            return provides(port, binding, "", endpointFactory);
        }

        public <T> Builder provides(
            ProvidedPort<T> port,
            PortBinding binding,
            String path,
            RuntimeEndpointFactory<T> endpointFactory
        ) {
            Objects.requireNonNull(port, "port must not be null");
            if (endpoints.stream().anyMatch(endpoint -> endpoint.port() == port)) {
                throw new IllegalArgumentException(
                    "Container plan declares port '" + port.qualifiedName() + "' more than once"
                );
            }
            endpoints.add(new TypedEndpoint<>(
                port,
                binding,
                path,
                endpointFactory
            ));
            return this;
        }

        public ContainerPlan build() {
            return new ContainerPlan(this);
        }
    }

    private interface DeclaredEndpoint {
        ProvidedPort<?> port();

        int containerPort();

        EndpointBinding<?> materialize(StartedContainer container);

        <O> void publishTo(ComponentRuntime.Builder<O> runtime, StartedContainer container);
    }

    private record TypedEndpoint<T>(
        ProvidedPort<T> port,
        PortBinding binding,
        String path,
        RuntimeEndpointFactory<T> endpointFactory
    ) implements DeclaredEndpoint {
        private TypedEndpoint {
            Objects.requireNonNull(port, "port must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            path = path == null ? "" : path;
            if (!path.isEmpty() && !path.startsWith("/")) {
                throw new IllegalArgumentException("Endpoint path must start with '/': " + path);
            }
            Objects.requireNonNull(endpointFactory, "endpointFactory must not be null");
        }

        @Override
        public int containerPort() {
            return binding.port();
        }

        @Override
        public EndpointBinding<T> materialize(StartedContainer container) {
            EndpointAddress internal = EndpointAddress.address(
                port.protocol().scheme(),
                TestcontainersDriver.networkAlias(port.owner()),
                binding.port(),
                path
            );
            EndpointAddress external = EndpointAddress.address(
                port.protocol().scheme(),
                container.host(),
                container.mappedPort(binding.port()),
                path
            );
            EndpointBinding<T> endpoint = Objects.requireNonNull(
                EndpointBinding.binding(
                    endpointFactory.create(internal),
                    endpointFactory.create(external)
                ),
                "Endpoint factory for '" + port.qualifiedName() + "' returned null binding"
            );
            Objects.requireNonNull(
                endpoint.internal(),
                "Endpoint factory for '" + port.qualifiedName() + "' returned null internal value"
            );
            Objects.requireNonNull(
                endpoint.external(),
                "Endpoint factory for '" + port.qualifiedName() + "' returned null external value"
            );
            port.contract().cast(endpoint.internal());
            port.contract().cast(endpoint.external());
            return endpoint;
        }

        @Override
        public <O> void publishTo(
            ComponentRuntime.Builder<O> runtime,
            StartedContainer container
        ) {
            runtime.provides(port, materialize(container));
        }
    }
}
