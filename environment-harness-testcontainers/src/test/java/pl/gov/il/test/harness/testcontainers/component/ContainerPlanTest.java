package pl.gov.il.test.harness.testcontainers.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.gov.il.test.harness.model.Contract.contract;
import static pl.gov.il.test.harness.testcontainers.component.PortBinding.port;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import pl.gov.il.test.harness.driver.ComponentDriver;
import pl.gov.il.test.harness.driver.DriverContext;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.RuntimeConfig;
import pl.gov.il.test.harness.model.ComponentId;
import pl.gov.il.test.harness.model.ComponentType;
import pl.gov.il.test.harness.model.Contract;
import pl.gov.il.test.harness.model.InteractionSpec;
import pl.gov.il.test.harness.model.ProtocolSpec;
import pl.gov.il.test.harness.model.ProvidedPort;

class ContainerPlanTest {
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<HttpEndpoint> API = contract("api", HttpEndpoint.class);
    private static final ComponentDriver<EmptyConfig, URI> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldDescribeContainerPortAndPathForALogicalProvidedPort() {
        Server server = new Server();
        ContainerPlan plan = ContainerPlan.container(new GenericContainer<>("alpine:3.20"))
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build();

        assertThat(plan.exposedPorts()).containsExactly(8080);
    }

    @Test
    void shouldRequireEveryProvidedPortInThePlan() {
        Server server = new Server();
        ContainerPlan plan = ContainerPlan.container(new GenericContainer<>("alpine:3.20")).build();

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
        AddressContainer container = new AddressContainer();
        ContainerPlan plan = ContainerPlan.container(container)
            .provides(server.api, port(8080), "/api", address ->
                new HttpEndpoint(URI.create(address.value())))
            .build();
        StartedContainer started = new StartedContainer(container, plan);

        var binding = started.binding(server.api);

        assertThat(binding.internal().uri()).hasToString("http://server.test:8080/api");
        assertThat(binding.external().uri()).hasToString("http://localhost:49152/api");
        assertThat(server.api.protocol().id()).isEqualTo("http");
    }

    @Test
    void shouldCreateDriverOperationsFromTheExternalRuntimeBinding() {
        Server server = new Server();
        AddressContainer container = new AddressContainer();
        StartedContainer started = new StartedContainer(
            container,
            ContainerPlan.container(container)
                .provides(server.api, port(8080), "/api", address ->
                    new HttpEndpoint(URI.create(address.value())))
                .build()
        );

        URI operationsEndpoint = new ExternalOperationsDriver().operations(server, started);

        assertThat(operationsEndpoint).hasToString("http://localhost:49152/api");
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
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return SERVER;
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

    private static final class AddressContainer extends GenericContainer<AddressContainer> {
        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public Integer getMappedPort(int originalPort) {
            return 49152;
        }
    }
}
