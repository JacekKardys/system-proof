package io.github.jacekkardys.systemproof.construction;

import static io.github.jacekkardys.systemproof.construction.ComponentPortFactory.requires;
import static io.github.jacekkardys.systemproof.construction.ComponentPortFactory.provides;

import static io.github.jacekkardys.systemproof.model.topology.Contract.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentType;
import io.github.jacekkardys.systemproof.model.topology.Connection;
import io.github.jacekkardys.systemproof.model.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.model.topology.PortRef;
import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;

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
        List<ConnectionRef> connections = new ArrayList<>(List.of(ConnectionFactory.create(client.api, server.api)));

        TopologyValidator.validate(components, connections);
        EnvironmentTopology topology = new EnvironmentTopology(components, connections);
        components.clear();
        connections.clear();

        assertThat(topology.components()).containsExactly(client, server);
        assertThat(topology.connections()).singleElement().isSameAs(topology.connectionFrom(client.api));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectAnUnsupportedComponentAtTheTopologyConstructionBoundary() {
        Component unsupported = new UnsupportedComponent();

        assertThatThrownBy(() -> new EnvironmentTopology((List) List.of(unsupported), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "EnvironmentTopology accepts only runtime components",
                AbstractComponent.class.getName(),
                UnsupportedComponent.class.getName()
            )
            .isNotInstanceOf(ClassCastException.class);
    }

    @Test
    void shouldRejectConnectionsToPortsOutsideTheDeclaredTopology() {
        Client client = new Client();
        Server declared = new Server("declared");
        Server outside = new Server("outside");

        assertThatThrownBy(() -> TopologyValidator.validate(List.of(client, declared),
            List.of(ConnectionFactory.create(client.api, outside.api))))
            .hasMessageContaining(
                "provided port [component='server-outside'",
                "localName='api'",
                "contractId='api'",
                "contractType='" + Api.class.getName() + "'",
                "interaction='invocation'",
                "protocol='http'"
            )
            .hasMessageContaining("is not owned by a component in this environment");
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
            super(ComponentId.component(CLIENT), new EmptyConfig(), Void.class, UNUSED);
            api = requires(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<Api> api;

        private Server(String qualifier) {
            super(ComponentId.component(SERVER, qualifier), new EmptyConfig(), Void.class, UNUSED);
            api = provides(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }
}
