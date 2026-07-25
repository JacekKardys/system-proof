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
            .hasToString("client.first%2Eapi->server.shared%2Eapi");
        assertThat(distinct.id())
            .hasToString("client.second-%3Eapi->server.shared%2Eapi")
            .isNotEqualTo(first.id());
        assertThat(first.from().contract()).isEqualTo(distinct.from().contract());
        assertThat(first.to()).isSameAs(distinct.to());
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

        @Override
        protected ComponentType componentType() {
            return CLIENT;
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> shared;

        private Server() {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, UNUSED);
            shared = provides("shared.api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return SERVER;
        }
    }
}
