package io.github.jacekkardys.systemproof.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.Contract;
import io.github.jacekkardys.systemproof.model.EndpointBinding;
import io.github.jacekkardys.systemproof.model.InteractionSpec;
import io.github.jacekkardys.systemproof.model.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RoutingMode;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

class ConnectionRoutingTest {
    private static final Contract<String> COMMAND = contract("command", String.class);
    private static final Contract<String> QUERY = contract("query", String.class);
    private static final Contract<Integer> COUNT = contract("count", Integer.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldDistinguishSemanticContractsUsingTheSameJavaType() {
        TestComponent client = new TestComponent("client");
        TestComponent server = new TestComponent("server");
        RequiredPort<String> commandRequired = client.required("command", COMMAND);
        RequiredPort<String> queryRequired = client.required("query", QUERY);
        Connection<String> command = Connection.connect(
            commandRequired,
            server.provided("command", COMMAND)
        );
        Connection<String> query = Connection.connect(
            queryRequired,
            server.provided("query", QUERY)
        );
        AtomicInteger routeCalls = new AtomicInteger();
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            (descriptor, directTarget) -> {
                routeCalls.incrementAndGet();
                return ConnectionRoute.routed(binding(
                    "routed-" + directTarget.internal(),
                    "routed-" + directTarget.external()
                ));
            }
        );

        RuntimeConnection<String> routed = materialize(
            command,
            routing,
            binding("command-direct", "command-external")
        );
        RuntimeConnection<String> direct = materialize(
            query,
            routing,
            binding("query-direct", "query-external")
        );

        assertThat(routed.routingMode()).isEqualTo(RoutingMode.ROUTED);
        assertThat(routed.resolve(commandRequired)).isEqualTo("routed-command-direct");
        assertThat(direct.routingMode()).isEqualTo(RoutingMode.DIRECT);
        assertThat(direct.resolve(queryRequired)).isEqualTo("query-direct");
        assertThat(routeCalls).hasValue(1);
    }

    @Test
    void shouldApplySeveralTypedRulesForDifferentJavaEndpointTypes() {
        TestComponent client = new TestComponent("client");
        TestComponent server = new TestComponent("server");
        RequiredPort<String> commandRequired = client.required("command", COMMAND);
        RequiredPort<Integer> countRequired = client.required("count", COUNT);
        Connection<String> command = Connection.connect(
            commandRequired,
            server.provided("command", COMMAND)
        );
        Connection<Integer> count = Connection.connect(
            countRequired,
            server.provided("count", COUNT)
        );
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            (descriptor, directTarget) -> ConnectionRoute.routed(binding(
                directTarget.internal().toUpperCase(),
                directTarget.external().toUpperCase()
            ))
        ).withRoute(
            COUNT,
            (descriptor, directTarget) -> ConnectionRoute.routed(binding(
                directTarget.internal() + 1,
                directTarget.external() + 1
            ))
        );

        RuntimeConnection<String> routedCommand = materialize(
            command,
            routing,
            binding("command", "command-external")
        );
        RuntimeConnection<Integer> routedCount = materialize(
            count,
            routing,
            binding(41, 81)
        );

        assertThat(routedCommand.resolve(commandRequired)).isEqualTo("COMMAND");
        assertThat(routedCount.resolve(countRequired)).isEqualTo(42);
        assertThat(routedCommand.routingMode()).isEqualTo(RoutingMode.ROUTED);
        assertThat(routedCount.routingMode()).isEqualTo(RoutingMode.ROUTED);
    }

    @Test
    void shouldPreferAStructuredConnectionRuleWithoutRoutingItsContractPeers() {
        TestComponent firstClient = new TestComponent("first");
        TestComponent secondClient = new TestComponent("second");
        TestComponent server = new TestComponent("server");
        RequiredPort<String> firstRequired = firstClient.required("command", COMMAND);
        RequiredPort<String> secondRequired = secondClient.required("command", COMMAND);
        ProvidedPort<String> provided = server.provided("command", COMMAND);
        Connection<String> first = Connection.connect(firstRequired, provided);
        Connection<String> second = Connection.connect(secondRequired, provided);
        ConnectionRouting routing = ConnectionRouting.routed(
            first,
            (descriptor, directTarget) -> ConnectionRoute.routed(binding(
                "first-route",
                "first-route-external"
            ))
        );

        RuntimeConnection<String> routed = materialize(
            first,
            routing,
            binding("direct", "direct-external")
        );
        RuntimeConnection<String> direct = materialize(
            second,
            routing,
            binding("direct", "direct-external")
        );

        assertThat(routed.resolve(firstRequired)).isEqualTo("first-route");
        assertThat(direct.resolve(secondRequired)).isEqualTo("direct");
        assertThat(routed.routingMode()).isEqualTo(RoutingMode.ROUTED);
        assertThat(direct.routingMode()).isEqualTo(RoutingMode.DIRECT);
    }

    private static <C> RuntimeConnection<C> materialize(
        Connection<C> declaration,
        ConnectionRouting routing,
        EndpointBinding<C> directTarget
    ) {
        RuntimeConnection<C> connection = new RuntimeConnection<>(
            declaration,
            routing.select(declaration)
        );
        connection.beginStartup();
        RuntimeConnection.RouteOwnership<C> ownership =
            connection.acquireRoute(directTarget);
        connection.bindTargets(connection.validateRoute(ownership));
        return connection;
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

    private static final class TestComponent extends AbstractComponent<EmptyConfig, Void> {
        private TestComponent(String qualifier) {
            super(
                ComponentId.component(ComponentType.of("routing-test"), qualifier),
                new EmptyConfig(),
                Void.class,
                UNUSED
            );
        }

        private <C> RequiredPort<C> required(String name, Contract<C> contract) {
            return requiresAtStartup(name, contract, Invocation.INSTANCE, Http.INSTANCE);
        }

        private <C> ProvidedPort<C> provided(String name, Contract<C> contract) {
            return provides(name, contract, Invocation.INSTANCE, Http.INSTANCE);
        }
    }
}
