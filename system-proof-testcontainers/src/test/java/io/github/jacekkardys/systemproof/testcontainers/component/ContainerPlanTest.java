package io.github.jacekkardys.systemproof.testcontainers.component;

import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import java.net.URI;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;

class ContainerPlanTest {
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<HttpEndpoint> API = contract("api", HttpEndpoint.class);
    private static final ComponentDriver<EmptyConfig, URI> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldDescribeContainerPortAndPathForALogicalProvidedPort() {
        Server server = new Server();
        ContainerPlan plan = ContainerPlan.container("alpine:3.20")
            .waitForHttp(port(8080), "/api", 200)
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build();

        assertThat(plan.exposedPorts()).containsExactly(8080);
    }

    @Test
    void shouldRequireEveryProvidedPortInThePlan() {
        Server server = new Server();
        ContainerPlan plan = ContainerPlan.container("alpine:3.20").build();

        assertThatThrownBy(() -> plan.validateFor(server))
            .hasMessageContaining("server", "server.api");
    }

    @Test
    void shouldRejectInvalidContainerPorts() {
        assertThat(port(2775).port()).isEqualTo(2775);
        assertThatThrownBy(() -> port(0))
            .hasMessage("Container port must be between 1 and 65535: 0");
    }

    @Test
    void shouldMaterializeInternalAndExternalAddressesWithoutChangingTheLogicalPort() {
        Server server = new Server();
        ContainerPlan plan = ContainerPlan.container("alpine:3.20")
            .waitForHttp(port(8080), "/api", 200)
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build();
        StartedContainer started = started(plan);

        var binding = started.binding(server.api);

        assertThat(binding.internal().uri()).hasToString("http://server.test:8080/api");
        assertThat(binding.external().uri()).hasToString("http://localhost:49152/api");
        assertThat(server.api.protocol().id()).isEqualTo("http");
    }

    @Test
    void shouldCreateDriverOperationsFromTheExternalRuntimeBinding() {
        Server server = new Server();
        StartedContainer started = new StartedContainer(
            () -> "localhost",
            ignored -> 49152,
            () -> true,
            ContainerPlan.container("alpine:3.20")
                .waitForHttp(port(8080), "/api", 200)
                .provides(server.api, port(8080), "/api", address ->
                    new HttpEndpoint(URI.create(address.value())))
                .build()
        );

        URI operationsEndpoint = new ExternalOperationsDriver().operations(server, started);

        assertThat(operationsEndpoint).hasToString("http://localhost:49152/api");
    }

    @Test
    void shouldRequireFrameworkOwnedReadinessForProvidedPorts() {
        Server server = new Server();

        assertThatThrownBy(() -> ContainerPlan.container("alpine:3.20")
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build())
            .hasMessage("Container plan with provided ports must declare a readiness probe");
    }

    @Test
    void shouldRejectReadinessForAnUndeclaredPort() {
        Server server = new Server();

        assertThatThrownBy(() -> ContainerPlan.container("alpine:3.20")
            .waitForListeningPorts(port(8081))
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build())
            .hasMessage("Readiness port is not declared as a provided endpoint: 8081");
    }

    @Test
    void shouldRejectAStoppedContainerBeforeRunningReadinessProbes() {
        Server server = new Server();
        ContainerPlan plan = ContainerPlan.container("alpine:3.20")
            .waitForHttp(port(8080), "/api", 200)
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build();
        StartedContainer stopped = new StartedContainer(
            () -> "localhost",
            ignored -> 49152,
            () -> false,
            plan
        );

        assertThatThrownBy(() -> plan.awaitReadiness(stopped))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Container readiness check failed");
    }

    private static StartedContainer started(ContainerPlan plan) {
        return new StartedContainer(
            () -> "localhost",
            ignored -> 49152,
            () -> true,
            plan
        );
    }

    private enum Invocation implements InteractionSpec {
        INSTANCE;
        public String id() { return "invocation"; }
    }

    private enum Http implements ProtocolSpec {
        INSTANCE;
        public String id() { return "http"; }
        public String scheme() { return "http"; }
    }

    private record HttpEndpoint(URI uri) {}
    private record EmptyConfig() implements RuntimeConfig {}

    private static final class Server extends AbstractComponent<EmptyConfig, URI> {
        private final ProvidedPort<HttpEndpoint> api;

        private Server() {
            super(ComponentId.component(SERVER), new EmptyConfig(), URI.class, UNUSED);
            api = provides(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static final class ExternalOperationsDriver
        extends TestcontainersDriver<EmptyConfig, URI, Server> {

        private ExternalOperationsDriver() {
            super(Server.class);
        }

        @Override
        protected ContainerPlan create(Server component, DriverContext context) {
            throw new AssertionError("Container creation should not run");
        }

        @Override
        protected URI createOperations(
            Server component,
            StartedContainer container,
            DriverContext context
        ) {
            return container.external(component.api).uri();
        }

        private URI operations(Server component, StartedContainer container) {
            return createOperations(component, container, null);
        }
    }

}
