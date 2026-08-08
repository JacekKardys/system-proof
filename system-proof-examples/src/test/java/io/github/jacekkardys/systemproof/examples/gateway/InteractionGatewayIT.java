package io.github.jacekkardys.systemproof.examples.gateway;

import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;
import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.environment.ConnectionRouting;
import io.github.jacekkardys.systemproof.environment.EnvironmentStartException;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.endpoint.EndpointAddress;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.configuration.Secret;
import io.github.jacekkardys.systemproof.endpoint.SmppEndpoint;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.StartedContainer;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;
import io.github.jacekkardys.systemproof.testcontainers.gateway.InteractionGateway;
import io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter;

class InteractionGatewayIT {
    private static final String PYTHON_IMAGE = "python:3.13.5-alpine3.22";
    private static final int PROVIDER_HTTP_PORT = 18_080;
    private static final int PROVIDER_SMPP_PORT = 2_775;
    private static final int CONSUMER_CONTROL_PORT = 19_090;
    private static final ComponentType PROVIDER = ComponentType.of("gateway-provider");
    private static final ComponentType CONSUMER = ComponentType.of("gateway-consumer");
    private static final Contract<EndpointAddress> HTTP =
        contract("gateway-http", EndpointAddress.class);
    private static final Contract<SmppEndpoint> SMPP =
        contract("gateway-smpp", SmppEndpoint.class);
    private static final Contract<EndpointAddress> CONTROL =
        contract("gateway-control", EndpointAddress.class);

    @Test
    void shouldCarryIndependentHttpAndLongLivedSessionTrafficAcrossContainerBoundaries()
        throws Exception {
        List<Integer> listenerPorts = new CopyOnWriteArrayList<>();
        ProviderDriver providerDriver = new ProviderDriver();
        ConsumerDriver consumerDriver = new ConsumerDriver();
        ProviderComponent provider = new ProviderComponent(providerDriver);
        ConsumerComponent consumer = new ConsumerComponent(consumerDriver);
        GatewayEnvironment environment = environment(
            provider,
            consumer,
            listenerPorts
        );

        try {
            environment.start();

            assertThat(environment.proof(consumer)).containsExactly(
                "http-provider",
                "smpp-provider:bind",
                "smpp-provider:submit-1",
                "smpp-provider:submit-2"
            );
            assertThat(environment.runtimeConnections())
                .hasSize(2)
                .allSatisfy(connection -> {
                    assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                    assertThat(connection.routingMode()).isEqualTo(RoutingMode.ROUTED);
                })
                .extracting(connection -> connection.descriptor().contractTypeName())
                .containsExactlyInAnyOrder(
                    EndpointAddress.class.getName(),
                    SmppEndpoint.class.getName()
                );
            assertThat(environment.runtimeConnections())
                .extracting(connection -> connection.descriptor().protocolId())
                .containsExactlyInAnyOrder("http", "smpp");
            assertThat(environment.runtimeConnections())
                .extracting(connection -> connection.id())
                .doesNotHaveDuplicates();
            assertThat(listenerPorts).hasSize(2).doesNotHaveDuplicates();
            assertDiagnosticsAreSecretSafe(
                environment,
                providerDriver,
                listenerPorts
            );
        } finally {
            environment.close();
        }

        assertThat(providerDriver.container()).isNotNull();
        assertThat(providerDriver.container().isRunning()).isFalse();
        assertThat(consumerDriver.container()).isNotNull();
        assertThat(consumerDriver.container().isRunning()).isFalse();
        listenerPorts.forEach(InteractionGatewayIT::assertPortCanBeRebound);
    }

    @Test
    void shouldReleaseGatewayAndProviderResourcesAfterConsumerStartupFails() {
        List<Integer> listenerPorts = new CopyOnWriteArrayList<>();
        IllegalStateException startupFailure =
            new IllegalStateException("Injected gateway consumer startup failure");
        ProviderDriver providerDriver = new ProviderDriver();
        ProviderComponent provider = new ProviderComponent(providerDriver);
        ConsumerComponent consumer = new ConsumerComponent((component, context) -> {
            ConsumerComponent typed = (ConsumerComponent) component;
            context.resolve(typed.http);
            context.resolve(typed.smpp);
            throw startupFailure;
        });
        GatewayEnvironment environment = environment(
            provider,
            consumer,
            listenerPorts
        );

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause()).isSameAs(startupFailure);
        assertThat(listenerPorts).hasSize(2).doesNotHaveDuplicates();
        assertThat(providerDriver.container()).isNotNull();
        assertThat(providerDriver.container().isRunning()).isFalse();
        assertThat(environment.runtimeConnections())
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(connection.directTargetAvailable()).isFalse();
                assertThat(connection.consumerTargetAvailable()).isFalse();
            });
        assertDiagnosticsAreSecretSafe(
            environment,
            providerDriver,
            listenerPorts
        );
        listenerPorts.forEach(InteractionGatewayIT::assertPortCanBeRebound);
    }

    private static GatewayEnvironment environment(
        ProviderComponent provider,
        ConsumerComponent consumer,
        List<Integer> listenerPorts
    ) {
        InteractionGateway gateway = new InteractionGateway();
        TcpEndpointAdapter<EndpointAddress> http = endpoint(
            value -> new InetSocketAddress(value.host(), value.port()),
            (value, host, port) -> {
                recordTestHostPort(host, port, listenerPorts);
                return EndpointAddress.address(
                    value.scheme(),
                    host,
                    port,
                    value.path()
                );
            }
        );
        TcpEndpointAdapter<SmppEndpoint> smpp = endpoint(
            value -> new InetSocketAddress(value.host(), value.port()),
            (value, host, port) -> {
                recordTestHostPort(host, port, listenerPorts);
                return new SmppEndpoint(
                    host,
                    port,
                    value.systemId(),
                    value.password()
                );
            }
        );
        EnvironmentBuilder builder = new EnvironmentBuilder()
            .components(consumer, provider)
            .connect(consumer.http, provider.http)
            .connect(consumer.smpp, provider.smpp);
        ConnectionRouting routing = ConnectionRouting.routed(
            HTTP,
            gateway.tcp(http)
        ).withRoute(
            SMPP,
            gateway.tcp(smpp)
        );
        return builder.build((topology, logging) ->
            new GatewayEnvironment(topology, logging, routing)
        );
    }

    private static void recordTestHostPort(
        String host,
        int port,
        List<Integer> listenerPorts
    ) {
        if ("127.0.0.1".equals(host)) {
            listenerPorts.add(port);
        }
    }

    private static void assertPortCanBeRebound(int port) {
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                port
            ));
            assertThat(rebound.isBound()).isTrue();
        } catch (IOException failure) {
            throw new AssertionError("Gateway listener port was not released", failure);
        }
    }

    private static void assertDiagnosticsAreSecretSafe(
        GatewayEnvironment environment,
        ProviderDriver providerDriver,
        List<Integer> listenerPorts
    ) {
        String diagnostics = environment.diagnostics().content();
        assertThat(diagnostics).doesNotContain(
            "spike-password",
            "system-proof-spike",
            "host.testcontainers.internal",
            "127.0.0.1"
        );
        listenerPorts.forEach(port ->
            assertThat(diagnostics).doesNotContain(Integer.toString(port))
        );
        assertThat(providerDriver.container()).isNotNull();
        if (providerDriver.container().isRunning()) {
            List.of(PROVIDER_HTTP_PORT, PROVIDER_SMPP_PORT)
                .stream()
                .map(providerDriver.container()::mappedPort)
                .forEach(port ->
                    assertThat(diagnostics).doesNotContain(Integer.toString(port))
                );
        }
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
        }
    }

    private enum Session implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "session";
        }
    }

    private enum Http implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "http";
        }

        @Override
        public String scheme() {
            return "http";
        }
    }

    private enum Smpp implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "smpp";
        }

        @Override
        public String scheme() {
            return "smpp";
        }
    }

    private static final class ProviderComponent extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<EndpointAddress> http;
        private final ProvidedPort<SmppEndpoint> smpp;

        private ProviderComponent(ComponentDriver<EmptyConfig, Void> driver) {
            super(ComponentId.component(PROVIDER), new EmptyConfig(), Void.class, driver);
            http = provides(this, "http", HTTP, Invocation.INSTANCE, Http.INSTANCE);
            smpp = provides(this, "smpp", SMPP, Session.INSTANCE, Smpp.INSTANCE);
        }
    }

    private static final class ConsumerComponent
        extends AbstractComponent<EmptyConfig, GatewayProofClient> {

        private final RequiredPort<EndpointAddress> http;
        private final RequiredPort<SmppEndpoint> smpp;
        private final ProvidedPort<EndpointAddress> control;

        private ConsumerComponent(
            ComponentDriver<EmptyConfig, GatewayProofClient> driver
        ) {
            super(
                ComponentId.component(CONSUMER),
                new EmptyConfig(),
                GatewayProofClient.class,
                driver
            );
            http = requiresAtStartup(this,
                "http",
                HTTP,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
            smpp = requiresAtStartup(this,
                "smpp",
                SMPP,
                Session.INSTANCE,
                Smpp.INSTANCE
            );
            control = provides(this,
                "control",
                CONTROL,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
        }
    }

    private static final class ProviderDriver
        extends TestcontainersDriver<EmptyConfig, Void, ProviderComponent> {

        private StartedContainer container;

        private ProviderDriver() {
            super(ProviderComponent.class);
        }

        @Override
        protected ContainerPlan create(
            ProviderComponent component,
            DriverContext context
        ) {
            return ContainerPlan.container(DockerImageName.parse(PYTHON_IMAGE))
                .copyToContainer(
                    MountableFile.forClasspathResource("gateway/provider.py"),
                    "/system-proof/provider.py"
                )
                .environment("HTTP_PORT", Integer.toString(PROVIDER_HTTP_PORT))
                .environment("SESSION_PORT", Integer.toString(PROVIDER_SMPP_PORT))
                .command("python", "/system-proof/provider.py")
                .waitForListeningPorts(
                    port(PROVIDER_HTTP_PORT),
                    port(PROVIDER_SMPP_PORT)
                )
                .provides(
                    component.http,
                    port(PROVIDER_HTTP_PORT),
                    "/route"
                )
                .provides(
                    component.smpp,
                    port(PROVIDER_SMPP_PORT),
                    address -> new SmppEndpoint(
                        address.host(),
                        address.port(),
                        "system-proof-spike",
                        Secret.secret("spike-password")
                    )
                )
                .build();
        }

        @Override
        protected void afterStart(
            ProviderComponent component,
            Void operations,
            StartedContainer container,
            DriverContext context
        ) {
            this.container = container;
        }

        private StartedContainer container() {
            return container;
        }
    }

    private static final class ConsumerDriver
        extends TestcontainersDriver<
            EmptyConfig,
            GatewayProofClient,
            ConsumerComponent
        > {

        private StartedContainer container;

        private ConsumerDriver() {
            super(ConsumerComponent.class);
        }

        @Override
        protected ContainerPlan create(
            ConsumerComponent component,
            DriverContext context
        ) {
            EndpointAddress http = context.resolve(component.http);
            SmppEndpoint smpp = context.resolve(component.smpp);
            return ContainerPlan.container(DockerImageName.parse(PYTHON_IMAGE))
                .accessToHost(true)
                .copyToContainer(
                    MountableFile.forClasspathResource("gateway/consumer.py"),
                    "/system-proof/consumer.py"
                )
                .environment("CONTROL_PORT", Integer.toString(CONSUMER_CONTROL_PORT))
                .environment("ROUTED_HTTP_HOST", http.host())
                .environment("ROUTED_HTTP_PORT", Integer.toString(http.port()))
                .environment("ROUTED_HTTP_PATH", http.path())
                .environment("ROUTED_SMPP_HOST", smpp.host())
                .environment("ROUTED_SMPP_PORT", Integer.toString(smpp.port()))
                .command("python", "/system-proof/consumer.py")
                .waitForListeningPorts(port(CONSUMER_CONTROL_PORT))
                .provides(
                    component.control,
                    port(CONSUMER_CONTROL_PORT),
                    "/proof"
                )
                .build();
        }

        @Override
        protected GatewayProofClient createOperations(
            ConsumerComponent component,
            StartedContainer container,
            DriverContext context
        ) {
            return new GatewayProofClient(
                URI.create(container.external(component.control).value())
            );
        }

        @Override
        protected void afterStart(
            ConsumerComponent component,
            GatewayProofClient operations,
            StartedContainer container,
            DriverContext context
        ) {
            this.container = container;
        }

        private StartedContainer container() {
            return container;
        }
    }

    private static final class GatewayEnvironment extends Environment {
        private GatewayEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, ConnectionRouting routing) {
            super(topology, logging, routing);
        }

        private List<String> proof(ConsumerComponent consumer) throws Exception {
            return operations(consumer).prove();
        }
    }

    private record GatewayProofClient(URI endpoint) {
        private List<String> prove() throws Exception {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "Gateway proof consumer returned HTTP " + response.statusCode()
                        + ": " + response.body()
                );
            }
            return response.body().lines().toList();
        }
    }
}
