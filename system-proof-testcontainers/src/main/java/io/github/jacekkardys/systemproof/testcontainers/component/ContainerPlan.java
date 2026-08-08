package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.Future;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.endpoint.EndpointAddress;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.topology.PortDirection;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;

/** A restricted System Proof-owned container specification and its typed provided ports. */
public final class ContainerPlan {
    private final ManagedGenericContainer container;
    private final List<DeclaredEndpoint> endpoints;
    private final ContainerReadiness readiness;

    private ContainerPlan(Builder builder) {
        container = builder.container;
        endpoints = List.copyOf(builder.endpoints);
        readiness = new ContainerReadiness(builder.readiness, builder.readinessTimeout);
    }

    public static Builder container(DockerImageName image) {
        return new Builder(image);
    }

    public static Builder container(String image) {
        return container(DockerImageName.parse(image));
    }

    public static Builder container(Future<String> image) {
        return new Builder(image);
    }

    ManagedGenericContainer container() {
        return container;
    }

    List<ProvidedPort<?>> ports() {
        return endpoints.stream().map(DeclaredEndpoint::port).toList();
    }

    Integer[] exposedPorts() {
        return endpoints.stream()
            .map(DeclaredEndpoint::containerPort)
            .distinct()
            .toArray(Integer[]::new);
    }

    void awaitReadiness(StartedContainer started) {
        readiness.await(started);
    }

    void validateFor(Component component) {
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
        private final ManagedGenericContainer container;
        private final List<DeclaredEndpoint> endpoints = new ArrayList<>();
        private final List<ContainerReadiness.Probe> readiness = new ArrayList<>();
        private Duration readinessTimeout = ContainerReadiness.defaultTimeout();

        private Builder(DockerImageName image) {
            container = new ManagedGenericContainer(
                Objects.requireNonNull(image, "container image must not be null")
            );
        }

        private Builder(Future<String> image) {
            container = new ManagedGenericContainer(
                Objects.requireNonNull(image, "container image must not be null")
            );
        }

        public Builder environment(String name, String value) {
            container.withEnv(
                Objects.requireNonNull(name, "environment name must not be null"),
                Objects.requireNonNull(value, "environment value must not be null")
            );
            return this;
        }

        public Builder command(String... command) {
            container.withCommand(Objects.requireNonNull(command, "command must not be null"));
            return this;
        }

        public Builder copyToContainer(Transferable file, String containerPath) {
            container.withCopyToContainer(
                Objects.requireNonNull(file, "container file must not be null"),
                Objects.requireNonNull(containerPath, "container path must not be null")
            );
            return this;
        }

        public Builder accessToHost(boolean enabled) {
            container.withAccessToHost(enabled);
            return this;
        }

        public Builder waitForListeningPorts(PortBinding... ports) {
            Objects.requireNonNull(ports, "readiness ports must not be null");
            for (PortBinding port : ports) {
                readiness.add(new ContainerReadiness.TcpProbe(
                    Objects.requireNonNull(port, "readiness port must not be null").port()
                ));
            }
            return this;
        }

        public Builder waitForHttp(
            PortBinding port,
            String path,
            int expectedStatus
        ) {
            readiness.add(new ContainerReadiness.HttpProbe(
                Objects.requireNonNull(port, "readiness port must not be null").port(),
                path,
                expectedStatus
            ));
            return this;
        }

        public Builder readinessTimeout(Duration timeout) {
            readinessTimeout = Objects.requireNonNull(
                timeout,
                "readiness timeout must not be null"
            );
            return this;
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
            if (!endpoints.isEmpty() && readiness.isEmpty()) {
                throw new IllegalStateException(
                    "Container plan with provided ports must declare a readiness probe"
                );
            }
            readiness.stream()
                .map(ContainerReadiness.Probe::port)
                .filter(port -> endpoints.stream()
                    .noneMatch(endpoint -> endpoint.containerPort() == port))
                .findFirst()
                .ifPresent(port -> {
                    throw new IllegalStateException(
                        "Readiness port is not declared as a provided endpoint: " + port
                    );
                });
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
