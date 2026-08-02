package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static io.github.jacekkardys.systemproof.api.EnvironmentLogging.logs;
import static io.github.jacekkardys.systemproof.driver.ComponentRuntime.runtime;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointAddress.address;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.construction.EnvironmentTopology;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.engine.ConnectionRoute;
import io.github.jacekkardys.systemproof.engine.ConnectionRouting;
import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;

class EnvironmentLifecycleTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<ApiEndpoint> API = contract("api", ApiEndpoint.class);

    @Test
    void shouldAttachTypedOperationsToTheSameComponentAndRejectCallsOutsideRunningState() {
        List<String> cleanup = new ArrayList<>();
        Server server = new Server((component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime(() -> cleanup.add("server"))
                .provides(((Server) component).api,
                    binding(
                        new ApiEndpoint(address("http", "server.test", 8080, "/api").value()),
                        new ApiEndpoint(address("http", "localhost", 49152, "/api").value())
                    ))
                .build()
        );
        Client client = new Client((component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<String>runtime(() -> cleanup.add("client"))
                .operations(context.resolve(((Client) component).api).value())
                .build()
        );
        Environment environment = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api)
            .build();
        RuntimeConnectionSnapshot declared = environment.runtimeConnections().getFirst();
        assertThat(declared.id()).isEqualTo(environment.connections().getFirst().id());
        assertThat(declared.state()).isEqualTo(ConnectionState.DECLARED);
        assertThat(declared.routingMode()).isEqualTo(RoutingMode.DIRECT);
        assertThat(declared.directTargetAvailable()).isFalse();
        assertThat(declared.consumerTargetAvailable()).isFalse();

        assertThatThrownBy(() -> environment.operations(client))
            .isInstanceOf(ComponentLifecycleException.class)
            .hasMessageContaining("client", "DECLARED", "RUNNING");

        assertThat(environment.start()).isSameAs(environment);
        assertThat(environment.operations(client)).isEqualTo("http://server.test:8080/api");
        assertThat(environment.componentState(client)).isEqualTo(ComponentState.RUNNING);
        assertThat(environment.runtimeConnection(declared.id()))
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(connection.directTargetAvailable()).isTrue();
                assertThat(connection.consumerTargetAvailable()).isTrue();
            });
        assertThat(environment.diagnostics().content())
            .contains(
                "[STATE] connection=" + declared.id(),
                "source=client[].api",
                "target=server[].api",
                "contract=api",
                "protocol=http",
                "mode=DIRECT",
                "state=RUNNING",
                "directTargetAvailable=true",
                "consumerTargetAvailable=true"
            )
            .doesNotContain(
                "http://server.test:8080/api",
                "http://localhost:49152/api"
            );

        environment.close();

        assertThat(cleanup).containsExactly("client", "server");
        assertThat(declared.state()).isEqualTo(ConnectionState.DECLARED);
        assertThat(declared.directTargetAvailable()).isFalse();
        assertThat(declared.consumerTargetAvailable()).isFalse();
        assertThat(environment.runtimeConnection(declared.id()))
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(connection.directTargetAvailable()).isFalse();
                assertThat(connection.consumerTargetAvailable()).isFalse();
            });
        assertThatThrownBy(() -> environment.operations(client))
            .isInstanceOf(ComponentLifecycleException.class)
            .hasMessageContaining("client", "STOPPED", "RUNNING");
        assertThat(events(environment, EnvironmentLifecycleEvent.class))
            .extracting(EnvironmentLifecycleEvent::state)
            .containsExactly(
                EnvironmentState.STARTING,
                EnvironmentState.RUNNING,
                EnvironmentState.STOPPING,
                EnvironmentState.STOPPED
            );
        assertThat(events(environment, ComponentLifecycleEvent.class))
            .extracting(event -> event.componentId().toString() + ":" + event.state())
            .containsExactly(
                "server:STARTING",
                "server:RUNNING",
                "client:STARTING",
                "client:RUNNING",
                "client:STOPPING",
                "client:STOPPED",
                "server:STOPPING",
                "server:STOPPED"
            );
        assertThat(events(environment, ConnectionLifecycleEvent.class))
            .extracting(ConnectionLifecycleEvent::state)
            .containsExactly(
                ConnectionState.DECLARED,
                ConnectionState.STARTING,
                ConnectionState.RUNNING,
                ConnectionState.STOPPING,
                ConnectionState.STOPPED
            );
    }

    @Test
    void shouldCaptureStoppedStartupFailureAfterAllCleanupFailures() {
        IllegalStateException cleanupFailure = new IllegalStateException("server cleanup failed");
        IllegalStateException sharedFailure = new IllegalStateException("shared cleanup failed");
        DriverResourceKey<FailingResource> key =
            DriverResourceKey.resourceKey("shared-network", FailingResource.class);
        Server server = new Server((component, context) -> {
            context.sharedResource(key, () -> new FailingResource(sharedFailure));
            return io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime(() -> {
                throw cleanupFailure;
            })
                .provides(((Server) component).api,
                    binding(
                        new ApiEndpoint(address("http", "server.test", 8080).value()),
                        new ApiEndpoint(address("http", "localhost", 49152).value())
                    ))
                .build();
        });
        Client client = new Client((component, context) -> {
            throw new IllegalStateException("client failed");
        });
        Environment environment = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api)
            .build();

        EnvironmentStartException failure = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(failure.getCause()).hasMessage("client failed");
        assertThat(failure.getCause().getSuppressed()).containsExactly(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(sharedFailure);
        assertThat(failure.diagnostics()).isEqualTo(environment.diagnostics());
        assertThat(failure.diagnostics().content())
            .contains(
                "[STATE] environment=STOPPED",
                "Environment startup failed",
                "Component startup failed",
                "client failed",
                "server cleanup failed",
                "shared-network",
                "shared cleanup failed",
                "component=server",
                "state=FAILED",
                "Environment failed",
                "Environment stopped"
            )
            .doesNotContain(
                "[STATE] environment=FAILED",
                "Environment stopped after startup failure"
            );
        assertThat(environment.state()).isEqualTo(EnvironmentState.STOPPED);
        assertThat(events(environment, EnvironmentLifecycleEvent.class))
            .extracting(EnvironmentLifecycleEvent::state)
            .containsExactly(
                EnvironmentState.STARTING,
                EnvironmentState.FAILED,
                EnvironmentState.STOPPED
            );
        assertThat(events(environment, ComponentLifecycleEvent.class))
            .extracting(event -> event.componentId().toString() + ":" + event.state())
            .containsExactly(
                "server:STARTING",
                "server:RUNNING",
                "client:STARTING",
                "client:FAILED",
                "server:STOPPING",
                "server:FAILED"
            );
        assertThat(events(environment, FailureEvent.ComponentStartup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.componentId()).isEqualTo(client.id());
                assertThat(event.failure().message()).contains("client failed");
            });
        assertThat(events(environment, FailureEvent.ComponentCleanup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.componentId()).isEqualTo(server.id());
                assertThat(event.failure().message()).contains("server cleanup failed");
            });
        assertThat(events(environment, FailureEvent.EnvironmentStartup.class))
            .singleElement()
            .satisfies(event -> assertThat(event.failure().message()).contains("client failed"));
        assertThat(events(environment, FailureEvent.DriverResourceCleanup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.resourceName()).isEqualTo("shared-network");
                assertThat(event.failure().message()).contains("shared cleanup failed");
            });
        assertThat(events(environment, FailureEvent.ConnectionCleanup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.connectionId())
                    .isEqualTo(environment.connections().getFirst().id());
                assertThat(event.failure().message()).contains("server cleanup failed");
            });
        assertThat(environment.runtimeConnections())
            .singleElement()
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.FAILED);
                assertThat(connection.directTargetAvailable()).isFalse();
            });
    }

    @Test
    void shouldRetainComponentAndSharedResourceCleanupFailuresAndSuppression() {
        IllegalStateException componentFailure =
            new IllegalStateException("component cleanup failed");
        IllegalStateException sharedFailure =
            new IllegalStateException("shared cleanup failed");
        DriverResourceKey<FailingResource> key =
            DriverResourceKey.resourceKey("shared-network", FailingResource.class);
        Server server = new Server((component, context) -> {
            context.sharedResource(key, () -> new FailingResource(sharedFailure));
            return io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime(() -> {
                throw componentFailure;
            })
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint(address("http", "server.test", 8080).value()),
                        new ApiEndpoint(address("http", "localhost", 49152).value())
                    )
                )
                .build();
        });
        Environment environment = new EnvironmentBuilder()
            .components(server)
            .build()
            .start();

        assertThatThrownBy(environment::close)
            .isSameAs(componentFailure)
            .satisfies(failure ->
                assertThat(failure.getSuppressed()).containsExactly(sharedFailure)
            );

        assertThat(environment.state()).isEqualTo(EnvironmentState.STOPPED);
        assertThat(environment.componentState(server)).isEqualTo(ComponentState.FAILED);
        assertThat(events(environment, FailureEvent.ComponentCleanup.class))
            .singleElement()
            .satisfies(event ->
                assertThat(event.failure().message()).contains("component cleanup failed")
            );
        assertThat(events(environment, FailureEvent.DriverResourceCleanup.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.resourceName()).isEqualTo("shared-network");
                assertThat(event.failure().message()).contains("shared cleanup failed");
            });
        assertThat(events(environment, EnvironmentLifecycleEvent.class))
            .extracting(EnvironmentLifecycleEvent::state)
            .containsExactly(
                EnvironmentState.STARTING,
                EnvironmentState.RUNNING,
                EnvironmentState.STOPPING,
                EnvironmentState.FAILED,
                EnvironmentState.STOPPED
            );
        assertThat(environment.diagnostics().content())
            .contains(
                "component cleanup failed",
                "shared-network",
                "shared cleanup failed",
                "[STATE] component=server type=server state=FAILED"
            );
    }

    @Test
    void shouldRenderDriverComponentEventsFromTheJournalBelowEmissionThresholds() {
        AtomicReference<String> observed = new AtomicReference<>();
        Server server = new Server((component, context) -> {
            context.log(
                component,
                LogLevel.DEBUG,
                "driver line one" + System.lineSeparator() + "driver line two"
            );
            observed.set(context.componentEvents(component));
            return io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime()
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint(address("http", "server.test", 8080).value()),
                        new ApiEndpoint(address("http", "localhost", 49152).value())
                    )
                )
                .build();
        });
        Environment environment = new EnvironmentBuilder()
            .components(server)
            .logging(io.github.jacekkardys.systemproof.api.EnvironmentLogging.logs()
                .warnByDefault())
            .build()
            .start();

        assertThat(observed.get())
            .contains(
                "[COMPONENT] [server] Starting component",
                "Configuration EmptyConfig",
                "driver line one",
                "driver line two"
            );
        List<DiagnosticEvent> diagnostics = events(environment, DiagnosticEvent.class);
        assertThat(diagnostics)
            .filteredOn(event -> event.message().startsWith("driver line one"))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.subject())
                    .isEqualTo(new DiagnosticEvent.ComponentSubject(server.id()));
                assertThat(event.level()).isEqualTo(LogLevel.DEBUG);
                assertThat(event.message()).isEqualTo(
                    "driver line one" + System.lineSeparator() + "driver line two"
                );
            });

        environment.close();
    }

    @Test
    void shouldAllowAnotherDriverWithoutChangingTheComponentOrLifecycle() {
        ComponentDriver<EmptyConfig, String> remoteDriver = (component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<String>runtime().operations("remote").build();
        Client component = new Client(remoteDriver);
        Server dependency = new Server((server, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime()
                .provides(((Server) server).api,
                    binding(
                        new ApiEndpoint(address("http", "remote", 443).value()),
                        new ApiEndpoint(address("https", "remote", 443).value())
                    ))
                .build()
        );
        Environment environment = new EnvironmentBuilder()
            .components(component, dependency)
            .connect(component.api, dependency.api)
            .build();

        environment.start();
        assertThat(environment.operations(component)).isEqualTo("remote");
        environment.close();
    }

    @Test
    void shouldStructureAConnectionFailureWhenAProviderOmitsItsPort() {
        Server server = new Server(
            (component, context) -> io.github.jacekkardys.systemproof.driver.ComponentRuntime
                .<Void>runtime()
                .build()
        );
        Client client = new Client(
            (component, context) -> io.github.jacekkardys.systemproof.driver.ComponentRuntime
                .<String>runtime()
                .operations("unused")
                .build()
        );
        Environment environment = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api)
            .build();

        assertThatThrownBy(environment::start)
            .isInstanceOf(EnvironmentStartException.class)
            .hasRootCauseMessage(
                "Driver for component 'server' did not materialize port 'server.api'"
            );

        assertThat(environment.runtimeConnections())
            .singleElement()
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.FAILED);
                assertThat(connection.directTargetAvailable()).isFalse();
            });
        assertThat(events(environment, FailureEvent.ConnectionMaterialization.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.connectionId())
                    .isEqualTo(environment.connections().getFirst().id());
                assertThat(event.failure().message())
                    .contains(
                        "Driver for component 'server' did not materialize port 'server.api'"
                    );
            });
    }

    @Test
    void shouldRejectResolvingARequiredPortOwnedByAnotherComponent() {
        AtomicReference<Client> other = new AtomicReference<>();
        Client intruder = new Client("intruder", (component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<String>runtime()
                .operations(context.resolve(other.get().api).value())
                .build()
        );
        Client victim = new Client(
            "victim",
            (component, context) ->
                io.github.jacekkardys.systemproof.driver.ComponentRuntime.<String>runtime()
                    .operations("unused")
                    .build()
        );
        other.set(victim);
        Server server = new Server((component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime()
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint("http://server.internal"),
                        new ApiEndpoint("http://server.external")
                    )
                )
                .build()
        );
        Environment environment = new EnvironmentBuilder()
            .components(intruder, victim, server)
            .connect(intruder.api, server.api)
            .connect(victim.api, server.api)
            .build();

        assertThatThrownBy(environment::start)
            .isInstanceOf(EnvironmentStartException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage(
                "Driver for component 'client-intruder' cannot resolve required port "
                    + "'client-victim.api' owned by component 'client-victim'"
            );
    }

    @Test
    void shouldPreserveStructuredCollisionIdsAcrossEveryRuntimeInspectionSurface() {
        CollisionProvider provider = new CollisionProvider();
        CollisionClient unqualified =
            new CollisionClient(ComponentId.component(ComponentType.of("client-a")));
        CollisionClient qualified =
            new CollisionClient(ComponentId.component(CLIENT, "a"));
        ConnectionId unqualifiedId = ConnectionId.between(
            unqualified.api,
            provider.api
        );
        ConnectionId qualifiedId = ConnectionId.between(qualified.api, provider.api);
        var logging = logs()
            .defaultConnectionLevel(LogLevel.OFF)
            .connectionLevel(unqualified.api, provider.api, LogLevel.DEBUG)
            .connectionLevel(qualified.api, provider.api, LogLevel.TRACE)
            .build();
        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(unqualified, qualified, provider)
                .connect(unqualified.api, provider.api)
                .connect(qualified.api, provider.api)
                .logging(logging),
            ConnectionRouting.routed(API, routeContext -> {
                ConnectionId connectionId = routeContext.connection().id();
                routeContext.observations().openSession().observe(
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    MutableInteractionEvidence.codec(),
                    new MutableInteractionEvidence(
                        connectionId.toString().getBytes(StandardCharsets.UTF_8),
                        new ArrayList<>()
                    )
                );
                return ConnectionRoute.routed(routeContext.directTarget());
            })
        )
            .start();

        assertThat(unqualified.id().toString()).isEqualTo(qualified.id().toString());
        assertThat(unqualifiedId).isNotEqualTo(qualifiedId);
        assertThat(environment.operations(unqualified)).isEqualTo("provider.internal");
        assertThat(environment.operations(qualified)).isEqualTo("provider.internal");
        assertThat(logging.connectionLevel(unqualifiedId)).isEqualTo(LogLevel.DEBUG);
        assertThat(logging.connectionLevel(qualifiedId)).isEqualTo(LogLevel.TRACE);
        assertThat(environment.runtimeConnections())
            .extracting(RuntimeConnectionSnapshot::id)
            .containsExactly(unqualifiedId, qualifiedId)
            .doesNotHaveDuplicates();
        assertThat(environment.runtimeConnection(unqualifiedId).descriptor())
            .satisfies(descriptor -> {
                assertThat(descriptor.id()).isEqualTo(unqualifiedId);
                assertThat(descriptor.sourceComponentId()).isEqualTo(unqualified.id());
            });
        assertThat(environment.runtimeConnection(qualifiedId).descriptor())
            .satisfies(descriptor -> {
                assertThat(descriptor.id()).isEqualTo(qualifiedId);
                assertThat(descriptor.sourceComponentId()).isEqualTo(qualified.id());
            });
        assertThat(environment.diagnostics().content())
            .contains(
                "[STATE] connection=" + unqualifiedId
                    + " source=client-a[].api target=provider[].api",
                "[STATE] connection=" + qualifiedId
                    + " source=client[a].api target=provider[].api",
                "[CONNECTION] [" + unqualifiedId + "]",
                "[CONNECTION] [" + qualifiedId + "]"
            );
        assertThat(events(environment, ConnectionLifecycleEvent.class))
            .filteredOn(event -> event.state() == ConnectionState.DECLARED)
            .extracting(event -> event.connection().id())
            .containsExactly(unqualifiedId, qualifiedId);
        assertThat(events(environment, ConnectionLifecycleEvent.class))
            .filteredOn(event -> event.state() == ConnectionState.DECLARED)
            .extracting(event -> event.connection().sourceComponentId())
            .containsExactly(unqualified.id(), qualified.id());
        assertThat(events(environment, InteractionObservationEvent.class))
            .extracting(event -> event.interactionRef().connectionId())
            .containsExactly(unqualifiedId, qualifiedId);

        environment.close();
    }

    @Test
    void shouldStopDeclaredConnectionsWhenClosedBeforeStartup() {
        Client client = new Client(
            (component, context) ->
                io.github.jacekkardys.systemproof.driver.ComponentRuntime.<String>runtime()
                    .build()
        );
        Server server = new Server((component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime()
                .provides(
                    ((Server) component).api,
                    binding(
                        new ApiEndpoint("http://server.internal"),
                        new ApiEndpoint("http://server.external")
                    )
                )
                .build()
        );
        Environment environment = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api)
            .build();

        environment.close();

        assertThat(environment.runtimeConnections())
            .singleElement()
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(connection.directTargetAvailable()).isFalse();
            });
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

    private record ApiEndpoint(String value) {}
    private record EmptyConfig() implements RuntimeConfig {}

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

    private record FailingResource(IllegalStateException failure) implements AutoCloseable {
        @Override
        public void close() {
            throw failure;
        }
    }

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
                ComponentId.component(CLIENT, qualifier),
                new EmptyConfig(),
                String.class,
                driver
            );
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

    private static final class CollisionClient
        extends AbstractComponent<EmptyConfig, String> {
        private final ComponentType type;
        private final RequiredPort<ApiEndpoint> api;

        private CollisionClient(ComponentId id) {
            super(
                id,
                new EmptyConfig(),
                String.class,
                (component, context) -> {
                    CollisionClient current = (CollisionClient) component;
                    return io.github.jacekkardys.systemproof.driver.ComponentRuntime
                        .<String>runtime()
                        .operations(context.resolve(current.api).value())
                        .build();
                }
            );
            type = id.type();
            api = requiresAtStartup(
                "api",
                API,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
        }

    }

    private static final class CollisionProvider
        extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("provider");
        private final ProvidedPort<ApiEndpoint> api;

        private CollisionProvider() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) ->
                    io.github.jacekkardys.systemproof.driver.ComponentRuntime
                    .<Void>runtime()
                    .provides(
                        ((CollisionProvider) component).api,
                        binding(
                            new ApiEndpoint("provider.internal"),
                            new ApiEndpoint("provider.external")
                        )
                    )
                    .build()
            );
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static RoutedEnvironment routedEnvironment(EnvironmentBuilder builder, ConnectionRouting routing) {
        return builder.build((topology, environmentLogging) ->
            new RoutedEnvironment(topology, environmentLogging, routing)
        );
    }

    private static final class RoutedEnvironment extends Environment {
        private RoutedEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, ConnectionRouting routing) {
            super(topology, logging, routing);
        }
    }
}
