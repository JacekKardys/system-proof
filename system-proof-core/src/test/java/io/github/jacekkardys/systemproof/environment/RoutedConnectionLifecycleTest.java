package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static io.github.jacekkardys.systemproof.endpoint.EndpointBinding.binding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

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
        EnvironmentBuilder builder = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api);
        Environment environment = routedEnvironment(
            builder,
            ConnectionRouting.routed(
                API,
                context -> {
                    var descriptor = context.connection();
                    var directTarget = context.directTarget();
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
    void shouldSanitizeRoutePreparationAndRollbackFailuresWithoutChangingTheThrownFailure() {
        String directInternal = "direct-internal-preparation-secret";
        String directExternal = "direct-external-preparation-secret";
        String routedInternal = "routed-internal-preparation-secret";
        String routedExternal = "routed-external-preparation-secret";
        IllegalStateException startupFailure = new IllegalStateException(
            "route preparation exposed " + directInternal + " " + directExternal
                + " " + routedInternal
        );
        IllegalStateException cleanupFailure = new IllegalStateException(
            "route rollback exposed " + routedInternal + " " + routedExternal
        );
        AtomicInteger preparations = new AtomicInteger();
        Server server = new Server((component, context) ->
            ComponentRuntime.<Void>runtime()
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint(directInternal),
                        new ApiEndpoint(directExternal)
                    )
                )
                .build()
        );
        Client first = new Client("first", (component, context) -> {
            throw new AssertionError("Consumer should not start");
        });
        Client second = new Client("second", (component, context) -> {
            throw new AssertionError("Consumer should not start");
        });
        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(first, second, server)
                .connect(first.api, server.api)
                .connect(second.api, server.api),
            ConnectionRouting.routed(
                API,
                context -> {
                    var directTarget = context.directTarget();
                    assertThat(directTarget.internal().value()).isEqualTo(directInternal);
                    assertThat(directTarget.external().value()).isEqualTo(directExternal);
                    if (preparations.incrementAndGet() == 2) {
                        throw startupFailure;
                    }
                    return ConnectionRoute.routed(
                        binding(
                            new ApiEndpoint(routedInternal),
                            new ApiEndpoint(routedExternal)
                        ),
                        () -> {
                            throw cleanupFailure;
                        }
                    );
                }
            )
        );
        ConnectionId firstId = environment.connections().get(0).id();
        ConnectionId secondId = environment.connections().get(1).id();

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause()).isSameAs(startupFailure);
        assertThat(startupFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(startupFailure).hasMessageContaining(
            directInternal,
            directExternal,
            routedInternal
        );
        assertThat(cleanupFailure).hasMessageContaining(routedInternal, routedExternal);

        String preparationContext =
            "Route preparation failed for connection '" + secondId + "'";
        String cleanupContext =
            "Route cleanup failed for connection '" + firstId + "'";
        assertThat(events(environment, FailureEvent.ConnectionMaterialization.class))
            .hasSize(2)
            .allSatisfy(event ->
                assertThat(event.failure().message()).contains(preparationContext)
            );
        assertThat(events(environment, FailureEvent.ConnectionCleanup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.connectionId()).isEqualTo(firstId);
                assertThat(event.failure().message()).contains(cleanupContext);
            });
        assertThat(events(environment, FailureEvent.ComponentStartup.class))
            .singleElement()
            .satisfies(event ->
                assertThat(event.failure().message()).contains(preparationContext)
            );
        assertThat(events(environment, FailureEvent.EnvironmentStartup.class))
            .singleElement()
            .satisfies(event ->
                assertThat(event.failure().message()).contains(preparationContext)
            );
        assertThat(thrown.diagnostics().content())
            .contains(preparationContext, cleanupContext)
            .doesNotContain(
                directInternal,
                directExternal,
                routedInternal,
                routedExternal
            );
    }

    @Test
    void shouldCloseARejectedRouteBeforeRollingBackEarlierRoutes() {
        String directInternal = "rejected-route-direct-internal-secret";
        String directExternal = "rejected-route-direct-external-secret";
        String firstInternal = "first-route-internal-secret";
        String firstExternal = "first-route-external-secret";
        String secondInternal = "second-route-internal-secret";
        String secondExternal = "second-route-external-secret";
        String rejectedInternal = "rejected-route-internal-secret";
        String rejectedExternal = "rejected-route-external-secret";
        String cleanupExceptionSecret = "rejected-route-cleanup-exception-secret";
        IllegalStateException cleanupFailure = new IllegalStateException(
            "route cleanup exposed " + directInternal + " " + directExternal + " "
                + rejectedInternal + " " + rejectedExternal + " "
                + cleanupExceptionSecret
        );
        List<String> secrets = List.of(
            directInternal,
            directExternal,
            firstInternal,
            firstExternal,
            secondInternal,
            secondExternal,
            rejectedInternal,
            rejectedExternal,
            cleanupExceptionSecret
        );
        List<ConnectionId> cleanupOrder = new ArrayList<>();
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger rejectedCleanupCalls = new AtomicInteger();
        AtomicInteger consumerStarts = new AtomicInteger();
        Server server = new Server((component, context) ->
            ComponentRuntime.<Void>runtime()
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint(directInternal),
                        new ApiEndpoint(directExternal)
                    )
                )
                .build()
        );
        Client first = new Client("first", (component, context) -> {
            consumerStarts.incrementAndGet();
            throw new AssertionError("Consumer should not start");
        });
        Client second = new Client("second", (component, context) -> {
            consumerStarts.incrementAndGet();
            throw new AssertionError("Consumer should not start");
        });
        Client rejected = new Client("rejected", (component, context) -> {
            consumerStarts.incrementAndGet();
            throw new AssertionError("Consumer should not start");
        });
        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(first, second, rejected, server)
                .connect(first.api, server.api)
                .connect(second.api, server.api)
                .connect(rejected.api, server.api),
            ConnectionRouting.routed(
                API,
                context -> {
                    var descriptor = context.connection();
                    var directTarget = context.directTarget();
                    assertThat(directTarget.internal().value()).isEqualTo(directInternal);
                    assertThat(directTarget.external().value()).isEqualTo(directExternal);
                    return switch (preparations.getAndIncrement()) {
                        case 0 -> ConnectionRoute.routed(
                            binding(
                                new ApiEndpoint(firstInternal),
                                new ApiEndpoint(firstExternal)
                            ),
                            () -> cleanupOrder.add(descriptor.id())
                        );
                        case 1 -> ConnectionRoute.routed(
                            binding(
                                new ApiEndpoint(secondInternal),
                                new ApiEndpoint(secondExternal)
                            ),
                            () -> cleanupOrder.add(descriptor.id())
                        );
                        case 2 -> ConnectionRoute.routed(
                            invalidApiBinding(rejectedInternal, rejectedExternal),
                            () -> {
                                rejectedCleanupCalls.incrementAndGet();
                                cleanupOrder.add(descriptor.id());
                                throw cleanupFailure;
                            }
                        );
                        default -> throw new AssertionError("Unexpected route preparation");
                    };
                }
            )
        );
        ConnectionId firstId = environment.connections().get(0).id();
        ConnectionId secondId = environment.connections().get(1).id();
        ConnectionId rejectedId = environment.connections().get(2).id();

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        Throwable validationFailure = thrown.getCause();
        assertThat(validationFailure).isInstanceOf(ClassCastException.class);
        assertThat(validationFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(cleanupFailure.getMessage()).contains(
            directInternal,
            directExternal,
            rejectedInternal,
            rejectedExternal,
            cleanupExceptionSecret
        );
        assertThat(preparations).hasValue(3);
        assertThat(rejectedCleanupCalls).hasValue(1);
        assertThat(cleanupOrder).containsExactly(rejectedId, secondId, firstId);
        assertThat(consumerStarts).hasValue(0);
        assertThat(environment.runtimeConnections())
            .hasSize(3)
            .allSatisfy(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.FAILED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        assertThat(
            environment.journalSnapshot().entries().stream()
                .map(entry -> entry.event())
                .filter(ConnectionLifecycleEvent.class::isInstance)
                .map(ConnectionLifecycleEvent.class::cast)
                .anyMatch(event -> event.state() == ConnectionState.RUNNING)
        ).isFalse();

        String preparationContext =
            "Route preparation failed for connection '" + rejectedId + "'";
        String cleanupContext =
            "Route cleanup failed for connection '" + rejectedId + "'";
        assertThat(events(environment, FailureEvent.ConnectionMaterialization.class))
            .hasSize(3)
            .allSatisfy(event ->
                assertThat(event.failure().message()).contains(preparationContext)
            );
        assertThat(events(environment, FailureEvent.ConnectionCleanup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.connectionId()).isEqualTo(rejectedId);
                assertThat(event.failure().message()).contains(cleanupContext);
            });
        List<String> journalFailureMessages =
            environment.journalSnapshot().entries().stream()
                .map(entry -> entry.event())
                .filter(FailureEvent.class::isInstance)
                .map(FailureEvent.class::cast)
                .map(event -> event.failure().message().orElse(""))
                .toList();
        assertThat(journalFailureMessages)
            .allSatisfy(message ->
                assertThat(message).doesNotContain(secrets.toArray(String[]::new))
            );
        String journalRendering = environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event().toString())
            .toList()
            .toString();
        assertThat(journalRendering).doesNotContain(secrets.toArray(String[]::new));
        assertThat(environment.diagnostics().content())
            .contains(preparationContext, cleanupContext)
            .doesNotContain(secrets.toArray(String[]::new));
    }

    @Test
    void shouldRollBackTheWholeBatchWhenLaterObservationInstallationFails() {
        String directInternal = "dynamic-direct-internal-secret";
        String directExternal = "dynamic-direct-external-secret";
        String routeInternal = "dynamic-route-internal-secret";
        String routeExternal = "dynamic-route-external-secret";
        String statusSecret = "dynamic-observation-status-secret";
        String firstCleanupSecret = "first-dynamic-cleanup-secret";
        String thirdCleanupSecret = "third-dynamic-cleanup-secret";
        IllegalStateException startupFailure = new IllegalStateException(
            "observation installation exposed " + statusSecret
        );
        IllegalStateException firstCleanupFailure = new IllegalStateException(
            "route cleanup exposed " + firstCleanupSecret
        );
        AssertionError thirdCleanupFailure = new AssertionError(
            "route cleanup exposed " + thirdCleanupSecret
        );
        AtomicInteger preparations = new AtomicInteger();
        List<ConnectionId> preparedIds = new ArrayList<>();
        List<ConnectionId> cleanupOrder = new ArrayList<>();
        List<AtomicInteger> statusCalls = List.of(
            new AtomicInteger(),
            new AtomicInteger(),
            new AtomicInteger()
        );
        List<AtomicInteger> cleanupCalls = List.of(
            new AtomicInteger(),
            new AtomicInteger(),
            new AtomicInteger()
        );
        Server server = new Server((component, context) ->
            ComponentRuntime.<Void>runtime()
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint(directInternal),
                        new ApiEndpoint(directExternal)
                    )
                )
                .build()
        );
        Client first = new Client("dynamic-first", (component, context) -> {
            throw new AssertionError("Consumer should not start");
        });
        Client second = new Client("dynamic-second", (component, context) -> {
            throw new AssertionError("Consumer should not start");
        });
        Client third = new Client("dynamic-third", (component, context) -> {
            throw new AssertionError("Consumer should not start");
        });
        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(first, second, third, server)
                .connect(first.api, server.api)
                .connect(second.api, server.api)
                .connect(third.api, server.api),
            ConnectionRouting.routed(
                API,
                ObservationRequirement.REQUIRED,
                context -> {
                    int routeIndex = preparations.getAndIncrement();
                    ConnectionId connectionId = context.connection().id();
                    preparedIds.add(connectionId);
                    return ConnectionRoute.routed(
                        binding(
                            new ApiEndpoint(routeInternal + "-" + routeIndex),
                            new ApiEndpoint(routeExternal + "-" + routeIndex)
                        ),
                        () -> {
                            int invocation = statusCalls.get(routeIndex).incrementAndGet();
                            if (routeIndex == 2 && invocation == 2) {
                                throw startupFailure;
                            }
                            return EffectiveObservationStatus.ACTIVE;
                        },
                        () -> {
                            cleanupCalls.get(routeIndex).incrementAndGet();
                            cleanupOrder.add(connectionId);
                            if (routeIndex == 2) {
                                throw thirdCleanupFailure;
                            }
                            if (routeIndex == 0) {
                                throw firstCleanupFailure;
                            }
                        }
                    );
                }
            )
        );

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );
        environment.close();

        assertThat(thrown.getCause()).isSameAs(startupFailure);
        assertThat(startupFailure.getSuppressed())
            .containsExactly(thirdCleanupFailure, firstCleanupFailure);
        assertThat(statusCalls).allSatisfy(calls -> assertThat(calls).hasValue(2));
        assertThat(cleanupCalls).allSatisfy(calls -> assertThat(calls).hasValue(1));
        assertThat(cleanupOrder).containsExactly(
            preparedIds.get(2),
            preparedIds.get(1),
            preparedIds.get(0)
        );
        assertThat(environment.runtimeConnections()).hasSize(3).allSatisfy(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ConnectionState.FAILED);
            assertThat(snapshot.directTargetAvailable()).isFalse();
            assertThat(snapshot.consumerTargetAvailable()).isFalse();
        });
        assertThat(
            events(environment, ConnectionLifecycleEvent.class).stream()
                .anyMatch(event -> event.state() == ConnectionState.RUNNING)
        ).isFalse();
        assertThat(events(environment, FailureEvent.ConnectionCleanup.class)).hasSize(2);
        assertThat(environment.diagnostics().content())
            .contains("Route preparation failed for connection '" + preparedIds.get(2) + "'")
            .doesNotContain(
                directInternal,
                directExternal,
                routeInternal,
                routeExternal,
                statusSecret,
                firstCleanupSecret,
                thirdCleanupSecret
            );
        assertThat(environment.journalSnapshot().entries())
            .map(entry -> entry.event().toString())
            .allSatisfy(rendered -> assertThat(rendered).doesNotContain(
                directInternal,
                directExternal,
                routeInternal,
                routeExternal,
                statusSecret,
                firstCleanupSecret,
                thirdCleanupSecret
            ));
    }

    @Test
    void shouldPreserveProviderCleanupAsSuppressedAfterRouteCleanupFails() {
        String directInternal = "direct-internal-cleanup-secret";
        String directExternal = "direct-external-cleanup-secret";
        String routedInternal = "routed-internal-cleanup-secret";
        String routedExternal = "routed-external-cleanup-secret";
        IllegalStateException routeFailure =
            new IllegalStateException(
                "route cleanup exposed " + directInternal + " " + directExternal
                    + " " + routedInternal + " " + routedExternal
            );
        IllegalStateException providerFailure =
            new IllegalStateException("provider cleanup failed");
        AtomicInteger routeCleanupCalls = new AtomicInteger();
        Server server = new Server((component, context) ->
            ComponentRuntime.<Void>runtime(() -> {
                throw providerFailure;
            })
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint(directInternal),
                        new ApiEndpoint(directExternal)
                    )
                )
                .build()
        );
        Client client = new Client((component, context) ->
            ComponentRuntime.<String>runtime()
                .operations(context.resolve(((Client) component).api).value())
                .build()
        );
        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(client, server)
                .connect(client.api, server.api),
            ConnectionRouting.routed(
                API,
                context -> ConnectionRoute.routed(
                    binding(
                        new ApiEndpoint(routedInternal),
                        new ApiEndpoint(routedExternal)
                    ),
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
            .hasMessageContaining(
                directInternal,
                directExternal,
                routedInternal,
                routedExternal
            )
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
            .satisfies(event -> {
                assertThat(event.connectionId())
                    .isEqualTo(environment.connections().getFirst().id());
                assertThat(event.failure().message()).contains(
                    "Route cleanup failed for connection '"
                        + environment.connections().getFirst().id() + "'"
                );
            });
        assertThat(events(environment, FailureEvent.ComponentCleanup.class))
            .singleElement()
            .satisfies(event ->
                assertThat(event.failure().message()).contains(
                    "Route cleanup failed for connection '"
                        + environment.connections().getFirst().id() + "'"
                )
            );
        assertThat(environment.diagnostics().content())
            .doesNotContain(
                directInternal,
                directExternal,
                routedInternal,
                routedExternal
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

    @SuppressWarnings("unchecked")
    private static EndpointBinding<ApiEndpoint> invalidApiBinding(
        String internal,
        String external
    ) {
        return (EndpointBinding<ApiEndpoint>) (EndpointBinding<?>)
            binding(internal, external);
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
            this(null, driver);
        }

        private Client(
            String qualifier,
            ComponentDriver<EmptyConfig, String> driver
        ) {
            super(
                qualifier == null
                    ? ComponentId.component(CLIENT)
                    : ComponentId.component(CLIENT, qualifier),
                new EmptyConfig(),
                String.class,
                driver
            );
            api = requiresAtStartup(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<ApiEndpoint> api;

        private Server(ComponentDriver<EmptyConfig, Void> driver) {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, driver);
            api = provides(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static RoutedEnvironment routedEnvironment(EnvironmentBuilder builder, ConnectionRouting routing) {
        return builder.build((topology, logging) ->
            new RoutedEnvironment(topology, logging, routing)
        );
    }

    private static final class RoutedEnvironment extends Environment {
        private RoutedEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, ConnectionRouting routing) {
            super(topology, logging, routing);
        }
    }
}
