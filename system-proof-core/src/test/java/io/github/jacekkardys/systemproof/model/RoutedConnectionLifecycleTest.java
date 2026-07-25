package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.engine.ConnectionRoute;
import io.github.jacekkardys.systemproof.engine.ConnectionRouting;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;

class RoutedConnectionLifecycleTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<ApiEndpoint> API = contract("api", ApiEndpoint.class);

    @Test
    void shouldPrepareTheRouteBeforeStartingAConsumerThatRequiresItsEndpoint() {
        List<String> lifecycle = new ArrayList<>();
        AtomicReference<Environment> environmentRef = new AtomicReference<>();
        Server server = new Server((component, context) -> {
            lifecycle.add("provider-start");
            return ComponentRuntime.<Void>runtime(() -> {
                RuntimeConnectionSnapshot snapshot =
                    environmentRef.get().runtimeConnections().getFirst();
                assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPING);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
                lifecycle.add("provider-close");
            })
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint("direct-secret-internal"),
                        new ApiEndpoint("direct-secret-external")
                    )
                )
                .build();
        });
        Client client = new Client((component, context) -> {
            ApiEndpoint endpoint = context.resolve(((Client) component).api);
            lifecycle.add("consumer-start:" + endpoint.value());
            return ComponentRuntime.<String>runtime(() -> lifecycle.add("consumer-close"))
                .operations(endpoint.value())
                .build();
        });
        Environment.Builder builder = Environment.environment()
            .components(client, server)
            .connect(client.api, server.api);
        Environment environment = new RoutedEnvironment(
            builder,
            ConnectionRouting.routed(
                ApiEndpoint.class,
                (descriptor, directTarget) -> {
                    assertThat(directTarget.internal().value())
                        .isEqualTo("direct-secret-internal");
                    lifecycle.add("route-ready:" + descriptor.id());
                    return ConnectionRoute.routed(
                        binding(
                            new ApiEndpoint("route-secret-internal"),
                            new ApiEndpoint("route-secret-external")
                        ),
                        () -> lifecycle.add("route-close")
                    );
                }
            )
        );
        environmentRef.set(environment);
        RuntimeConnectionSnapshot declared = environment.runtimeConnections().getFirst();

        environment.start();

        assertThat(environment.operations(client)).isEqualTo("route-secret-internal");
        assertThat(lifecycle).containsExactly(
            "provider-start",
            "route-ready:" + declared.id(),
            "consumer-start:route-secret-internal"
        );
        assertThat(environment.runtimeConnection(declared.id()))
            .satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(snapshot.routingMode()).isEqualTo(RoutingMode.ROUTED);
                assertThat(snapshot.directTargetAvailable()).isTrue();
                assertThat(snapshot.consumerTargetAvailable()).isTrue();
            });
        assertThat(declared)
            .satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.DECLARED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        assertThat(environment.diagnostics().content())
            .contains(
                "mode=ROUTED",
                "directTargetAvailable=true",
                "consumerTargetAvailable=true"
            )
            .doesNotContain(
                "direct-secret-internal",
                "direct-secret-external",
                "route-secret-internal",
                "route-secret-external"
            );

        environment.close();

        assertThat(lifecycle).containsExactly(
            "provider-start",
            "route-ready:" + declared.id(),
            "consumer-start:route-secret-internal",
            "consumer-close",
            "route-close",
            "provider-close"
        );
        assertThat(environment.runtimeConnection(declared.id()))
            .satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
    }

    @Test
    void shouldPreserveProviderCleanupAsSuppressedAfterRouteCleanupFails() {
        IllegalStateException routeFailure =
            new IllegalStateException("route cleanup failed");
        IllegalStateException providerFailure =
            new IllegalStateException("provider cleanup failed");
        AtomicInteger routeCleanupCalls = new AtomicInteger();
        Server server = new Server((component, context) ->
            ComponentRuntime.<Void>runtime(() -> {
                throw providerFailure;
            })
                .provides(
                    ((Server) component).api,
                    binding(new ApiEndpoint("direct"), new ApiEndpoint("direct-external"))
                )
                .build()
        );
        Client client = new Client((component, context) ->
            ComponentRuntime.<String>runtime()
                .operations(context.resolve(((Client) component).api).value())
                .build()
        );
        Environment environment = new RoutedEnvironment(
            Environment.environment()
                .components(client, server)
                .connect(client.api, server.api),
            ConnectionRouting.routed(
                ApiEndpoint.class,
                (descriptor, directTarget) -> ConnectionRoute.routed(
                    binding(new ApiEndpoint("route"), new ApiEndpoint("route-external")),
                    () -> {
                        routeCleanupCalls.incrementAndGet();
                        throw routeFailure;
                    }
                )
            )
        );
        environment.start();

        assertThatThrownBy(environment::close)
            .isSameAs(routeFailure)
            .satisfies(failure ->
                assertThat(failure.getSuppressed()).containsExactly(providerFailure)
            );

        assertThat(routeCleanupCalls).hasValue(1);
        assertThat(environment.runtimeConnections())
            .singleElement()
            .satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.FAILED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        assertThat(events(environment, FailureEvent.ConnectionCleanup.class))
            .singleElement()
            .satisfies(event ->
                assertThat(event.failure().message()).contains("route cleanup failed")
            );
    }

    private static <T extends ScenarioEvent> List<T> events(
        Environment environment,
        Class<T> eventType
    ) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(eventType::isInstance)
            .map(eventType::cast)
            .toList();
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

    private record ApiEndpoint(String value) {}

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class Client extends AbstractComponent<EmptyConfig, String> {
        private final RequiredPort<ApiEndpoint> api;

        private Client(ComponentDriver<EmptyConfig, String> driver) {
            super(ComponentId.component(CLIENT), new EmptyConfig(), String.class, driver);
            api = requiresAtStartup("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<ApiEndpoint> api;

        private Server(ComponentDriver<EmptyConfig, Void> driver) {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, driver);
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class RoutedEnvironment extends Environment {
        private RoutedEnvironment(Builder builder, ConnectionRouting routing) {
            super(builder, routing);
        }
    }
}
