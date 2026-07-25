package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.api.EnvironmentLogging.logs;
import static io.github.jacekkardys.systemproof.model.Contract.contract;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

class EnvironmentTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<Api> API = contract("api", Api.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldModelOwnedTypedConnections() {
        Client client = new Client();
        Server server = new Server(null, API);

        Environment environment = Environment.environment()
            .components(client, server)
            .connect(client.api, server.api)
            .build();

        assertThat(environment.connections()).singleElement().satisfies(connection -> {
            assertThat(connection.from()).isSameAs(client.api);
            assertThat(connection.to()).isSameAs(server.api);
        });
        assertThat(server.api.owner()).isSameAs(server);
        assertThat(server.api).extracting(PortRef::contractId, port -> port.interaction().id(),
            port -> port.protocol().id()).containsExactly("api", "invocation", "http");
    }

    @Test
    void shouldRequireEveryRequiredPortExactlyOnce() {
        Client client = new Client();
        Server first = new Server("first", API);
        Server second = new Server("second", API);

        assertThatThrownBy(() -> Environment.environment().components(client, first).build())
            .hasMessageContaining(
                "required port [component='client'",
                "localName='api'",
                "contractId='api'",
                "contractType='" + Api.class.getName() + "'",
                "interaction='invocation'",
                "protocol='http'"
            )
            .hasMessageContaining("is not connected");

        assertThatThrownBy(() -> Environment.environment()
            .components(client, first, second)
            .connect(client.api, first.api)
            .connect(client.api, second.api)
            .build())
            .hasMessageContaining(
                "required port [component='client'",
                "localName='api'",
                "contractId='api'",
                "provided port [component='server-first'",
                "provided port [component='server-second'",
                "is connected more than once"
            );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectContractInteractionAndProtocolMismatchesWithBothPortIdentities() {
        Client client = new Client();
        Server server = new Server(null, contract("different-api", Api.class));

        assertThatThrownBy(() -> Environment.environment()
            .components(client, server)
            .connect((RequiredPort) client.api, (ProvidedPort) server.api))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "component='client'",
                "component='server'",
                "localName='api'",
                "contractId='api'",
                "contractId='different-api'",
                "contractType='" + Api.class.getName() + "'",
                "interaction='invocation'",
                "protocol='http'",
                "contract id mismatch"
            );
    }

    @Test
    void shouldSupportSeveralQualifiedInstancesOfOneComponentType() {
        Server primary = new Server(null, API);
        Server secondary = new Server("secondary", API);

        assertThat(primary.id()).isEqualTo(ComponentId.component(SERVER));
        assertThat(primary.id().toString()).isEqualTo("server");
        assertThat(secondary.id().toString()).isEqualTo("server-secondary");
        assertThatThrownBy(() -> Environment.environment()
            .components(primary, new Server(null, API))
            .build())
            .hasMessage("Duplicate component ID 'server'");
    }

    @Test
    void shouldKeepFrameworkComponentAndConnectionLoggingLevels() {
        Client client = new Client();
        Server server = new Server(null, API);
        var logging = logs()
            .frameworkLevel(LogLevel.WARN)
            .warnByDefault()
            .info(server)
            .connectionLevel(client.api, server.api, LogLevel.DEBUG)
            .build();
        Environment environment = Environment.environment()
            .components(client, server)
            .connect(client.api, server.api)
            .logging(logging)
            .build();

        assertThat(logging.frameworkLevel()).isEqualTo(LogLevel.WARN);
        assertThat(logging.componentLevel(client)).isEqualTo(LogLevel.WARN);
        assertThat(logging.componentLevel(server)).isEqualTo(LogLevel.INFO);
        assertThat(logging.connectionLevel(environment.connections().getFirst())).isEqualTo(LogLevel.DEBUG);
        assertThat(logging.connectionLevel(environment.connections().getFirst().id()))
            .isEqualTo(LogLevel.DEBUG);
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

    private interface Api {}
    private record EmptyConfig() implements RuntimeConfig {}

    private static final class Client extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<Api> api;

        private Client() {
            super(ComponentId.component(CLIENT), new EmptyConfig(), Void.class, UNUSED);
            api = requires("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return CLIENT;
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<Api> api;

        private Server(String qualifier, Contract<Api> contract) {
            super(ComponentId.component(SERVER, qualifier), new EmptyConfig(), Void.class, UNUSED);
            api = provides("api", contract, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return SERVER;
        }
    }
}
