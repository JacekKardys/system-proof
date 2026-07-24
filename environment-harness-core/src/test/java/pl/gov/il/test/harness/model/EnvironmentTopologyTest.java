package pl.gov.il.test.harness.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.gov.il.test.harness.model.Contract.contract;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.gov.il.test.harness.driver.ComponentDriver;

class EnvironmentTopologyTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<Api> API = contract("api", Api.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldOwnAnImmutableTopologyDeclaration() {
        Client client = new Client();
        Server server = new Server("server");
        List<AbstractComponent<?, ?>> components = new ArrayList<>(List.of(client, server));
        List<ConnectionRef> connections = new ArrayList<>(List.of(
            Connection.connect(client.api, server.api)
        ));

        EnvironmentTopology topology = new EnvironmentTopology(components, connections);
        components.clear();
        connections.clear();

        assertThat(topology.components()).containsExactly(client, server);
        assertThat(topology.connections()).singleElement().isSameAs(topology.connectionFrom(client.api));
        assertThat(topology.componentDefinitions()).containsExactly(client, server);
    }

    @Test
    void shouldRejectConnectionsToPortsOutsideTheDeclaredTopology() {
        Client client = new Client();
        Server declared = new Server("declared");
        Server outside = new Server("outside");

        assertThatThrownBy(() -> TopologyValidator.validate(
            List.of(client, declared),
            List.of(Connection.connect(client.api, outside.api))
        )).hasMessage("Port 'server-outside.api' is not owned by a component in this environment");
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

        private Server(String qualifier) {
            super(ComponentId.component(SERVER, qualifier), new EmptyConfig(), Void.class, UNUSED);
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return SERVER;
        }
    }
}
