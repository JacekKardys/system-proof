package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.Contract.contract;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

class ConnectionIdTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<String> API = contract("api", String.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldDeriveDeterministicCollisionSafeIdentityFromBothPortIdentities() {
        Client firstClient = new Client();
        Server firstServer = new Server();
        Client secondClient = new Client();
        Server secondServer = new Server();

        Connection<String> first = Connection.connect(
            firstClient.first,
            firstServer.shared
        );
        Connection<String> equivalent = Connection.connect(
            secondClient.first,
            secondServer.shared
        );
        Connection<String> distinct = Connection.connect(
            firstClient.second,
            firstServer.shared
        );

        assertThat(first.id())
            .isEqualTo(equivalent.id())
            .hasToString("client[].first%2Eapi->server[].shared%2Eapi");
        assertThat(distinct.id())
            .hasToString("client[].second-%3Eapi->server[].shared%2Eapi")
            .isNotEqualTo(first.id());
        assertThat(first.from().contract()).isEqualTo(distinct.from().contract());
        assertThat(first.to()).isSameAs(distinct.to());
    }

    @Test
    void shouldKeepComponentTypeAndQualifierAsSeparateIdentityFields() {
        ComponentId unqualifiedId = ComponentId.component(ComponentType.of("client-a"));
        ComponentId qualifiedId = ComponentId.component(ComponentType.of("client"), "a");
        NamedClient unqualified = new NamedClient(unqualifiedId, "api");
        NamedClient qualified = new NamedClient(qualifiedId, "api");
        NamedServer provider = new NamedServer(
            ComponentId.component(ComponentType.of("provider")),
            "api"
        );

        Connection<String> unqualifiedConnection = Connection.connect(
            unqualified.api,
            provider.api
        );
        Connection<String> qualifiedConnection = Connection.connect(
            qualified.api,
            provider.api
        );

        assertThat(unqualifiedId)
            .isNotEqualTo(qualifiedId);
        assertThat(unqualifiedId.toString())
            .isEqualTo(qualifiedId.toString())
            .isEqualTo("client-a");
        assertThat(unqualifiedConnection.id())
            .hasToString("client-a[].api->provider[].api")
            .isNotEqualTo(qualifiedConnection.id());
        assertThat(qualifiedConnection.id())
            .hasToString("client[a].api->provider[].api");
        assertThat(ConnectionId.of(unqualifiedConnection.id().toString()))
            .isEqualTo(unqualifiedConnection.id());
        assertThat(ConnectionId.of(qualifiedConnection.id().toString()))
            .isEqualTo(qualifiedConnection.id());

        Environment environment = Environment.environment()
            .components(unqualified, qualified, provider)
            .connect(unqualified.api, provider.api)
            .connect(qualified.api, provider.api)
            .build();

        assertThat(environment.connections())
            .extracting(ConnectionRef::id)
            .containsExactly(
                unqualifiedConnection.id(),
                qualifiedConnection.id()
            )
            .doesNotHaveDuplicates();
        assertThat(environment.connection(unqualifiedConnection.id()).from().owner().id())
            .isEqualTo(unqualifiedId);
        assertThat(environment.connection(qualifiedConnection.id()).from().owner().id())
            .isEqualTo(qualifiedId);
    }

    @Test
    void shouldKeepTargetIdentityAndPortDelimitersCollisionSafe() {
        NamedClient client = new NamedClient(
            ComponentId.component(ComponentType.of("client")),
            "api"
        );
        NamedServer unqualified = new NamedServer(
            ComponentId.component(ComponentType.of("provider-a")),
            "api"
        );
        NamedServer qualified = new NamedServer(
            ComponentId.component(ComponentType.of("provider"), "a"),
            "api"
        );
        Connection<String> unqualifiedTarget = Connection.connect(
            client.api,
            unqualified.api
        );
        Connection<String> qualifiedTarget = Connection.connect(
            client.api,
            qualified.api
        );

        assertThat(unqualified.id())
            .isNotEqualTo(qualified.id());
        assertThat(unqualified.id().toString()).isEqualTo(qualified.id().toString());
        assertThat(unqualifiedTarget.id())
            .hasToString("client[].api->provider-a[].api")
            .isNotEqualTo(qualifiedTarget.id());
        assertThat(qualifiedTarget.id())
            .hasToString("client[].api->provider[a].api");

        DelimitedClient delimited = new DelimitedClient();
        NamedServer provider = new NamedServer(
            ComponentId.component(ComponentType.of("provider")),
            "api"
        );
        Connection<String> delimiter = Connection.connect(
            delimited.delimiter,
            provider.api
        );
        Connection<String> encodedText = Connection.connect(
            delimited.encodedText,
            provider.api
        );

        assertThat(delimiter.id())
            .hasToString("client[].api%2Epart->provider[].api")
            .isNotEqualTo(encodedText.id());
        assertThat(encodedText.id())
            .hasToString("client[].api%252Epart->provider[].api");
    }

    @Test
    void shouldRejectDescriptorWhoseIdDisagreesWithItsStructuredEndpoints() {
        NamedClient unqualified = new NamedClient(
            ComponentId.component(ComponentType.of("client-a")),
            "api"
        );
        NamedClient qualified = new NamedClient(
            ComponentId.component(ComponentType.of("client"), "a"),
            "api"
        );
        NamedServer provider = new NamedServer(
            ComponentId.component(ComponentType.of("provider")),
            "api"
        );
        Connection<String> unqualifiedConnection = Connection.connect(
            unqualified.api,
            provider.api
        );
        Connection<String> qualifiedConnection = Connection.connect(
            qualified.api,
            provider.api
        );

        ConnectionDescriptor descriptor = ConnectionDescriptor.from(
            unqualifiedConnection
        );

        assertThat(descriptor.id()).isEqualTo(unqualifiedConnection.id());
        assertThat(descriptor.sourceComponentId()).isEqualTo(unqualified.id());
        assertThatThrownBy(() -> new ConnectionDescriptor(
            qualifiedConnection.id(),
            unqualified.id(),
            unqualified.api.name(),
            provider.id(),
            provider.api.name(),
            API.id(),
            API.contractType().getName(),
            Invocation.INSTANCE.id(),
            Http.INSTANCE.id(),
            Http.INSTANCE.scheme()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "does not match its structured endpoints",
                "expected '" + unqualifiedConnection.id() + "'"
            );
    }

    @Test
    void shouldUseTheTypedIdentityForDeclarationLookupAndRejectDuplicates() {
        Client client = new Client();
        Server server = new Server();
        ConnectionId expected = ConnectionId.between(client.first, server.shared);
        Environment environment = Environment.environment()
            .components(client, server)
            .connect(client.first, server.shared)
            .connect(client.second, server.shared)
            .build();

        assertThat(environment.connection(expected).id()).isEqualTo(expected);
        assertThat(environment.connections())
            .extracting(ConnectionRef::id)
            .doesNotHaveDuplicates();

        Client duplicateClient = new Client();
        Server duplicateServer = new Server();
        assertThatThrownBy(() -> Environment.environment()
            .components(duplicateClient, duplicateServer)
            .connect(duplicateClient.first, duplicateServer.shared)
            .connect(duplicateClient.first, duplicateServer.shared)
            .connect(duplicateClient.second, duplicateServer.shared)
            .build())
            .hasMessageContaining(
                "Duplicate connection '"
                    + ConnectionId.between(duplicateClient.first, duplicateServer.shared)
                    + "'"
            );
    }

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
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

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class Client extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<String> first;
        private final RequiredPort<String> second;

        private Client() {
            super(ComponentId.component(CLIENT), new EmptyConfig(), Void.class, UNUSED);
            first = requires("first.api", API, Invocation.INSTANCE, Http.INSTANCE);
            second = requires("second->api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> shared;

        private Server() {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, UNUSED);
            shared = provides("shared.api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static final class NamedClient extends AbstractComponent<EmptyConfig, Void> {
        private final ComponentType type;
        private final RequiredPort<String> api;

        private NamedClient(ComponentId id, String portName) {
            super(id, new EmptyConfig(), Void.class, UNUSED);
            type = id.type();
            api = requires(portName, API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static final class NamedServer extends AbstractComponent<EmptyConfig, Void> {
        private final ComponentType type;
        private final ProvidedPort<String> api;

        private NamedServer(ComponentId id, String portName) {
            super(id, new EmptyConfig(), Void.class, UNUSED);
            type = id.type();
            api = provides(portName, API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static final class DelimitedClient extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<String> delimiter;
        private final RequiredPort<String> encodedText;

        private DelimitedClient() {
            super(ComponentId.component(CLIENT), new EmptyConfig(), Void.class, UNUSED);
            delimiter = requires("api.part", API, Invocation.INSTANCE, Http.INSTANCE);
            encodedText = requires("api%2Epart", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }
}
