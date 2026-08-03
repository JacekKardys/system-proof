package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requires;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.topology.Connection;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.PortRef;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

class EnvironmentTopologyTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<Api> API = contract("api", Api.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldValidateAndRetainTheSameImmutableTopologySnapshotsInDeclarationOrder() {
        Client firstClient = new Client("first");
        Server firstServer = new Server("first");
        Client secondClient = new Client("second");
        Server secondServer = new Server("second");
        ConnectionRef firstConnection = ConnectionFactory.create(firstClient.api, firstServer.api);
        ConnectionRef secondConnection = ConnectionFactory.create(secondClient.api, secondServer.api);
        List<AbstractComponent<?, ?>> components = new ArrayList<>(List.of(
            firstClient,
            firstServer,
            secondClient,
            secondServer
        ));
        List<ConnectionRef> connections = new ArrayList<>(List.of(
            secondConnection,
            firstConnection
        ));

        EnvironmentTopology topology = EnvironmentTopology.of(components, connections);
        components.clear();
        connections.clear();

        assertThat(topology.components()).containsExactly(
            firstClient,
            firstServer,
            secondClient,
            secondServer
        );
        assertThat(topology.connections()).containsExactly(secondConnection, firstConnection);
        assertThat(topology.connectionFrom(firstClient.api)).isSameAs(firstConnection);
        assertThat(topology.connectionFrom(secondClient.api)).isSameAs(secondConnection);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectAnUnsupportedComponentAtTheTopologyConstructionBoundary() {
        Component unsupported = new UnsupportedComponent();

        assertThatThrownBy(() -> EnvironmentTopology.of((List) List.of(unsupported), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "EnvironmentTopology accepts only runtime components",
                AbstractComponent.class.getName(),
                UnsupportedComponent.class.getName()
            )
            .isNotInstanceOf(ClassCastException.class);
    }

    @Test
    void shouldRejectConnectionsToComponentsOutsideTheDeclaredTopology() {
        Client client = new Client();
        Server declared = new Server("declared");
        Server outside = new Server("outside");

        assertThatThrownBy(() -> EnvironmentTopology.of(
            List.of(client, declared),
            List.of(ConnectionFactory.create(client.api, outside.api))
        ))
            .hasMessageContaining(
                "provided port [component='server-outside'",
                "localName='api'",
                "contractId='api'",
                "contractType='" + Api.class.getName() + "'",
                "interaction='invocation'",
                "protocol='http'",
                "belongs to a component outside this environment"
            );
    }

    @Test
    void shouldRejectAConnectionToAPortNotRegisteredByItsComponent() {
        Client client = new Client();
        Server server = new Server("declared");
        RequiredPort<Api> detached = new RequiredPort<>(
            client,
            "detached",
            API,
            Invocation.INSTANCE,
            Http.INSTANCE,
            false
        );

        assertThatThrownBy(() -> EnvironmentTopology.of(
            List.of(client, server),
            List.of(ConnectionFactory.create(detached, server.api))
        ))
            .hasMessageContaining(
                "required port [component='client'",
                "localName='detached'",
                "is not the exact port instance registered by component 'client'"
            );
    }

    @Test
    void shouldRejectDuplicateComponentIds() {
        Server first = new Server("duplicate");
        Server second = new Server("duplicate");

        assertThatThrownBy(() -> EnvironmentTopology.of(List.of(first, second), List.of()))
            .hasMessage("Duplicate component ID 'server-duplicate'");
    }

    @Test
    void shouldRejectDuplicateConnectionIds() {
        Client client = new Client();
        Server server = new Server("declared");
        ConnectionRef connection = ConnectionFactory.create(client.api, server.api);

        assertThatThrownBy(() -> EnvironmentTopology.of(
            List.of(client, server),
            List.of(connection, connection)
        ))
            .hasMessageContaining("Duplicate connection '" + connection.id() + "'");
    }

    @Test
    void shouldRejectARequiredPortConnectedMoreThanOnce() {
        Client client = new Client();
        Server first = new Server("first");
        Server second = new Server("second");

        assertThatThrownBy(() -> EnvironmentTopology.of(
            List.of(client, first, second),
            List.of(
                ConnectionFactory.create(client.api, first.api),
                ConnectionFactory.create(client.api, second.api)
            )
        ))
            .hasMessageContaining(
                "required port [component='client'",
                "is connected more than once",
                "server-first",
                "server-second"
            );
    }

    @Test
    void shouldRejectAnUnconnectedRequiredPort() {
        Client client = new Client();

        assertThatThrownBy(() -> EnvironmentTopology.of(List.of(client), List.of()))
            .hasMessageContaining(
                "required port [component='client'",
                "localName='api'",
                "is not connected"
            );
    }

    @Test
    void shouldRejectAConnectionIdThatDoesNotMatchItsEndpoints() {
        Client client = new Client();
        Server declared = new Server("declared");
        Server other = new Server("other");
        ConnectionId inconsistentId = ConnectionId.between(client.api, other.api);
        Connection<Api> inconsistent = new Connection<>(client.api, declared.api, inconsistentId);

        assertThatThrownBy(() -> EnvironmentTopology.of(
            List.of(client, declared),
            List.of(inconsistent)
        ))
            .hasMessageContaining(
                "Connection ID mismatch",
                "declared='" + inconsistentId + "'",
                "expected='" + ConnectionId.between(client.api, declared.api) + "'",
                "required port [component='client'",
                "provided port [component='server-declared'"
            );
    }

    @Test
    void shouldRejectAHandConstructedConnectionWithMismatchedContractId() {
        Client client = new Client();
        Server server = new Server(
            "contract",
            contract("other-api", Api.class),
            Invocation.INSTANCE,
            Http.INSTANCE
        );

        assertThatThrownBy(() -> topologyWith(new Connection<>(
            client.api,
            server.api,
            ConnectionId.between(client.api, server.api)
        ), client, server))
            .hasMessageContaining(
                "contract id mismatch",
                "contractId='api'",
                "contractId='other-api'"
            );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectAHandConstructedConnectionWithMismatchedContractType() {
        Client client = new Client();
        OtherServer server = new OtherServer();
        ConnectionRef connection = new Connection(
            (RequiredPort) client.api,
            (ProvidedPort) server.api,
            ConnectionId.between(client.api, server.api)
        );

        assertThatThrownBy(() -> topologyWith(connection, client, server))
            .hasMessageContaining(
                "contract type mismatch",
                Api.class.getName(),
                OtherApi.class.getName()
            );
    }

    @Test
    void shouldRejectAHandConstructedConnectionWithMismatchedInteraction() {
        Client client = new Client(
            "interaction",
            API,
            Messaging.INSTANCE,
            Http.INSTANCE,
            UNUSED
        );
        Server server = new Server("interaction");

        assertThatThrownBy(() -> topologyWith(new Connection<>(
            client.api,
            server.api,
            ConnectionId.between(client.api, server.api)
        ), client, server))
            .hasMessageContaining(
                "required interaction 'messaging'",
                "interaction='messaging'",
                "interaction='invocation'"
            );
    }

    @Test
    void shouldRejectAHandConstructedConnectionWithMismatchedProtocol() {
        Client client = new Client(
            "protocol",
            API,
            Invocation.INSTANCE,
            Grpc.INSTANCE,
            UNUSED
        );
        Server server = new Server("protocol");

        assertThatThrownBy(() -> topologyWith(new Connection<>(
            client.api,
            server.api,
            ConnectionId.between(client.api, server.api)
        ), client, server))
            .hasMessageContaining(
                "required protocol 'grpc'",
                "protocol='grpc'",
                "protocol='http'"
            );
    }

    @Test
    void shouldRejectNullAndEmptyInputsAtThePublicFactory() {
        Server server = new Server("declared");

        assertThatThrownBy(() -> EnvironmentTopology.of(null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("components must not be null");
        assertThatThrownBy(() -> EnvironmentTopology.of(List.of(server), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("connections must not be null");
        assertThatThrownBy(() -> EnvironmentTopology.of(
            Arrays.asList((AbstractComponent<?, ?>) null),
            List.of()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("components must not contain null");
        assertThatThrownBy(() -> EnvironmentTopology.of(
            List.of(server),
            Arrays.asList((ConnectionRef) null)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("connections must not contain null");
        assertThatThrownBy(() -> EnvironmentTopology.of(List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Environment must contain at least one component");
    }

    @Test
    void shouldFailBeforeFacadeRuntimeDriverOrResourceCreation() {
        AtomicInteger facadeCreations = new AtomicInteger();
        AtomicInteger driverStarts = new AtomicInteger();
        AtomicInteger resourceCloses = new AtomicInteger();
        ComponentDriver<EmptyConfig, Void> driver = (component, context) -> {
            driverStarts.incrementAndGet();
            return ComponentRuntime.<Void>runtime(resourceCloses::incrementAndGet).build();
        };
        Client client = new Client(
            "boundary",
            API,
            Invocation.INSTANCE,
            Http.INSTANCE,
            driver
        );

        assertThatThrownBy(() -> {
            EnvironmentTopology topology = EnvironmentTopology.of(List.of(client), List.of());
            facadeCreations.incrementAndGet();
            new Environment(topology, EnvironmentLogging.defaults()).start();
        })
            .hasMessageContaining("required port", "is not connected");

        assertThat(facadeCreations).hasValue(0);
        assertThat(driverStarts).hasValue(0);
        assertThat(resourceCloses).hasValue(0);
    }

    private static EnvironmentTopology topologyWith(
        ConnectionRef connection,
        AbstractComponent<?, ?> from,
        AbstractComponent<?, ?> to
    ) {
        return EnvironmentTopology.of(List.of(from, to), List.of(connection));
    }

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
        }
    }

    private enum Messaging implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "messaging";
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

    private enum Grpc implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "grpc";
        }

        @Override
        public String scheme() {
            return "grpc";
        }
    }

    private interface Api {}

    private interface OtherApi {}

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class UnsupportedComponent implements Component {
        @Override
        public ComponentId id() {
            return ComponentId.component(ComponentType.of("unsupported"));
        }

        @Override
        public ComponentType type() {
            return id().type();
        }

        @Override
        public RuntimeConfig configuration() {
            return new EmptyConfig();
        }

        @Override
        public List<PortRef> ports() {
            return List.of();
        }
    }

    private static final class Client extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<Api> api;

        private Client() {
            this(null, API, Invocation.INSTANCE, Http.INSTANCE, UNUSED);
        }

        private Client(String qualifier) {
            this(qualifier, API, Invocation.INSTANCE, Http.INSTANCE, UNUSED);
        }

        private Client(
            String qualifier,
            Contract<Api> contract,
            InteractionSpec interaction,
            ProtocolSpec protocol,
            ComponentDriver<EmptyConfig, Void> driver
        ) {
            super(
                qualifier == null
                    ? ComponentId.component(CLIENT)
                    : ComponentId.component(CLIENT, qualifier),
                new EmptyConfig(),
                Void.class,
                driver
            );
            api = requires(this, "api", contract, interaction, protocol);
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<Api> api;

        private Server(String qualifier) {
            this(qualifier, API, Invocation.INSTANCE, Http.INSTANCE);
        }

        private Server(
            String qualifier,
            Contract<Api> contract,
            InteractionSpec interaction,
            ProtocolSpec protocol
        ) {
            super(
                ComponentId.component(SERVER, qualifier),
                new EmptyConfig(),
                Void.class,
                UNUSED
            );
            api = provides(this, "api", contract, interaction, protocol);
        }
    }

    private static final class OtherServer extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<OtherApi> api;

        private OtherServer() {
            super(
                ComponentId.component(SERVER, "other-type"),
                new EmptyConfig(),
                Void.class,
                UNUSED
            );
            api = provides(
                this,
                "api",
                contract("api", OtherApi.class),
                Invocation.INSTANCE,
                Http.INSTANCE
            );
        }
    }
}
