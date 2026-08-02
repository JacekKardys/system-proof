package io.github.jacekkardys.systemproof.engine;

import static io.github.jacekkardys.systemproof.construction.ComponentPorts.requiresAtStartup;
import static io.github.jacekkardys.systemproof.construction.ComponentPorts.provides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.topology.Contract.contract;
import static io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding.binding;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentType;
import io.github.jacekkardys.systemproof.model.topology.Connection;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.model.runtime.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.model.runtime.ObservationRequirement;
import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;
import io.github.jacekkardys.systemproof.model.runtime.RoutingMode;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;

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
        Connection<String> command = connection(
            commandRequired,
            server.provided("command", COMMAND)
        );
        Connection<String> query = connection(
            queryRequired,
            server.provided("query", QUERY)
        );
        AtomicInteger routeCalls = new AtomicInteger();
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            context -> {
                routeCalls.incrementAndGet();
                return ConnectionRoute.routed(binding(
                    "routed-" + context.directTarget().internal(),
                    "routed-" + context.directTarget().external()
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
        Connection<String> command = connection(
            commandRequired,
            server.provided("command", COMMAND)
        );
        Connection<Integer> count = connection(
            countRequired,
            server.provided("count", COUNT)
        );
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            context -> ConnectionRoute.routed(binding(
                context.directTarget().internal().toUpperCase(),
                context.directTarget().external().toUpperCase()
            ))
        ).withRoute(
            COUNT,
            context -> ConnectionRoute.routed(binding(
                context.directTarget().internal() + 1,
                context.directTarget().external() + 1
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
        Connection<String> first = connection(firstRequired, provided);
        Connection<String> second = connection(secondRequired, provided);
        ConnectionRouting routing = ConnectionRouting.routed(
            first,
            context -> ConnectionRoute.routed(binding(
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

    @Test
    void shouldKeepRoutingAndObservationRequirementOrthogonal() {
        TestComponent client = new TestComponent("observation");
        TestComponent server = new TestComponent("observation");
        RequiredPort<String> required = client.required("command", COMMAND);
        Connection<String> declaration = connection(
            required,
            server.provided("command", COMMAND)
        );
        EndpointBinding<String> target = binding("direct", "external");

        RuntimeConnection<String> direct = materialize(
            declaration,
            ConnectionRouting.direct(),
            target
        );
        RuntimeConnection<String> routedDisabled = materialize(
            declaration,
            ConnectionRouting.routed(
                declaration,
                context -> ConnectionRoute.routed(target)
            ),
            target
        );
        RuntimeConnection<String> routedOptional = materialize(
            declaration,
            ConnectionRouting.routed(
                declaration,
                ObservationRequirement.OPTIONAL,
                context -> routeWithStatus(target, EffectiveObservationStatus.UNSUPPORTED)
            ),
            target
        );
        RuntimeConnection<String> routedRequired = materialize(
            declaration,
            ConnectionRouting.routed(
                declaration,
                ObservationRequirement.REQUIRED,
                context -> routeWithStatus(target, EffectiveObservationStatus.ACTIVE)
            ),
            target
        );

        assertThat(direct.snapshot())
            .extracting(
                snapshot -> snapshot.routingMode(),
                snapshot -> snapshot.observationRequirement(),
                snapshot -> snapshot.effectiveObservationStatus()
            )
            .containsExactly(
                RoutingMode.DIRECT,
                ObservationRequirement.DISABLED,
                EffectiveObservationStatus.DISABLED
            );
        assertThat(routedDisabled.snapshot())
            .extracting(
                snapshot -> snapshot.routingMode(),
                snapshot -> snapshot.observationRequirement(),
                snapshot -> snapshot.effectiveObservationStatus()
            )
            .containsExactly(
                RoutingMode.ROUTED,
                ObservationRequirement.DISABLED,
                EffectiveObservationStatus.DISABLED
            );
        assertThat(routedOptional.snapshot())
            .extracting(
                snapshot -> snapshot.routingMode(),
                snapshot -> snapshot.observationRequirement(),
                snapshot -> snapshot.effectiveObservationStatus()
            )
            .containsExactly(
                RoutingMode.ROUTED,
                ObservationRequirement.OPTIONAL,
                EffectiveObservationStatus.UNSUPPORTED
            );
        assertThat(routedRequired.snapshot())
            .extracting(
                snapshot -> snapshot.routingMode(),
                snapshot -> snapshot.observationRequirement(),
                snapshot -> snapshot.effectiveObservationStatus()
            )
            .containsExactly(
                RoutingMode.ROUTED,
                ObservationRequirement.REQUIRED,
                EffectiveObservationStatus.ACTIVE
            );
    }

    @Test
    void shouldRejectRequiredObservationThatSilentlyUsesATransparentRoute() {
        TestComponent client = new TestComponent("required");
        TestComponent server = new TestComponent("required");
        Connection<String> declaration = connection(
            client.required("command", COMMAND),
            server.provided("command", COMMAND)
        );
        ConnectionRouting routing = ConnectionRouting.routed(
            declaration,
            ObservationRequirement.REQUIRED,
            context -> ConnectionRoute.routed(context.directTarget())
        );

        assertThatThrownBy(() -> materialize(
            declaration,
            routing,
            binding("direct", "external")
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                declaration.id().toString(),
                "requires active observation",
                "DISABLED"
            );
    }

    private static <C> Connection<C> connection(RequiredPort<C> from, ProvidedPort<C> to) {
        return new Connection<>(from, to, ConnectionId.between(from, to));
    }

    private static <C> ConnectionRoute<C> routeWithStatus(
        EndpointBinding<C> target,
        EffectiveObservationStatus status
    ) {
        return ConnectionRoute.routed(target, () -> status, () -> {});
    }

    private static <C> RuntimeConnection<C> materialize(
        Connection<C> declaration,
        ConnectionRouting routing,
        EndpointBinding<C> directTarget
    ) {
        RuntimeConnection<C> connection = new RuntimeConnection<>(
            declaration,
            routing.select(declaration),
            () -> {
                throw new AssertionError("Observation capability should not be used");
            },
            interactionRef -> ForwardingDecision.FORWARD
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
            return requiresAtStartup(this, name, contract, Invocation.INSTANCE, Http.INSTANCE);
        }

        private <C> ProvidedPort<C> provided(String name, Contract<C> contract) {
            return provides(this, name, contract, Invocation.INSTANCE, Http.INSTANCE);
        }
    }
}
