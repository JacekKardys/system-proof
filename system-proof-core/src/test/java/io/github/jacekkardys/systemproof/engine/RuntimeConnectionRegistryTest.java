package io.github.jacekkardys.systemproof.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.driver.ComponentRuntime.runtime;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.ConnectionState;
import io.github.jacekkardys.systemproof.model.Contract;
import io.github.jacekkardys.systemproof.model.InteractionSpec;
import io.github.jacekkardys.systemproof.model.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RoutingMode;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.RuntimeConnectionSnapshot;

class RuntimeConnectionRegistryTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<String> API = contract("api", String.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldMaterializeExactlyOnceInTopologyOrderAndBindOneSharedProviderEndpoint() {
        Client first = new Client("first");
        Client second = new Client("second");
        Server server = new Server();
        Connection<String> firstDeclaration = Connection.connect(first.api, server.api);
        Connection<String> secondDeclaration = Connection.connect(second.api, server.api);
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        RuntimeConnectionRegistry registry = registry(
            List.of(firstDeclaration, secondDeclaration),
            journal
        );

        List<RuntimeConnectionSnapshot> declared = registry.snapshots();
        assertThat(declared)
            .extracting(RuntimeConnectionSnapshot::id)
            .containsExactly(firstDeclaration.id(), secondDeclaration.id());
        assertThat(declared)
            .extracting(RuntimeConnectionSnapshot::state)
            .containsOnly(ConnectionState.DECLARED);
        assertThatThrownBy(() -> declared.add(declared.getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.connection(firstDeclaration.id()))
            .isSameAs(registry.connection(firstDeclaration.id()));
        assertThat(registry.connection(firstDeclaration.id()).declaration())
            .isSameAs(firstDeclaration);

        registry.beginStartup();
        assertThatThrownBy(() -> registry.resolve(first.api))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(firstDeclaration.id().toString(), "STARTING");

        ComponentRuntime<Void> provider = ComponentRuntime.<Void>runtime()
            .provides(
                server.api,
                binding("internal-secret-endpoint", "external-secret-endpoint")
            )
            .build();
        var prepared = registry.prepareTargets(server, provider);
        assertThat(prepared).hasSize(2);
        registry.bindTargets(prepared);

        assertThat(registry.resolve(first.api)).isEqualTo("internal-secret-endpoint");
        assertThat(registry.resolve(second.api)).isEqualTo("internal-secret-endpoint");
        assertThat(registry.connection(firstDeclaration.id()).directTarget())
            .satisfies(target -> {
                assertThat(target.internal()).isEqualTo("internal-secret-endpoint");
                assertThat(target.external()).isEqualTo("external-secret-endpoint");
            });
        assertThat(registry.connection(firstDeclaration.id()).consumerTarget())
            .isEqualTo(registry.connection(firstDeclaration.id()).directTarget());
        assertThat(registry.snapshots())
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(connection.routingMode()).isEqualTo(RoutingMode.DIRECT);
                assertThat(connection.directTargetAvailable()).isTrue();
                assertThat(connection.consumerTargetAvailable()).isTrue();
            });
        assertThat(declared)
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.DECLARED);
                assertThat(connection.directTargetAvailable()).isFalse();
                assertThat(connection.consumerTargetAvailable()).isFalse();
            });
        assertThatThrownBy(() -> registry.prepareTargets(server, provider))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot bind a direct target from state RUNNING");

        assertThat(registry.beginProviderCleanup(server)).isNull();
        assertThatThrownBy(() -> registry.resolve(first.api))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(firstDeclaration.id().toString(), "STOPPING");
        registry.completeProviderCleanup(server);

        assertThat(registry.snapshots())
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(connection.directTargetAvailable()).isFalse();
                assertThat(connection.consumerTargetAvailable()).isFalse();
            });
        assertThat(journal.snapshot().entries())
            .map(entry -> entry.event())
            .filteredOn(ConnectionLifecycleEvent.class::isInstance)
            .hasSize(10);
        assertThat(new EnvironmentEventLog(journal, EnvironmentLogging.defaults())
            .snapshot()
            .content())
            .doesNotContain("internal-secret-endpoint", "external-secret-endpoint");
    }

    @Test
    void shouldRejectDuplicateMaterializationAndHideAllRuntimeMutators() {
        Client client = new Client("only");
        Server server = new Server();
        ConnectionRef declaration = Connection.connect(client.api, server.api);

        assertThatThrownBy(() -> registry(
            List.of(declaration, declaration),
            new ScenarioJournal(() -> 0L)
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Runtime connection '" + declaration.id() + "' was materialized more than once"
            );
        assertThat(RuntimeConnection.class.getMethods())
            .extracting(Method::getName)
            .doesNotContain(
                "beginStartup",
                "prepareTargets",
                "bindTargets",
                "beginStopping",
                "closeRoute",
                "invalidateDirectTarget",
                "completeStopping",
                "fail"
            );
        assertThat(ConnectionRoute.class.getMethods())
            .extracting(Method::getName)
            .doesNotContain("consumerTarget", "close");
    }

    @Test
    void shouldCentrallyRejectRepeatedAndBackwardLifecycleTransitions() {
        Client client = new Client("state");
        Server server = new Server();
        RuntimeConnection<String> connection = new RuntimeConnection<>(
            Connection.connect(client.api, server.api)
        );

        assertThatThrownBy(connection::beginStopping)
            .hasMessageContaining("cannot begin stopping from state DECLARED");
        connection.beginStartup();
        assertThatThrownBy(connection::beginStartup)
            .hasMessageContaining("cannot transition from STARTING to STARTING");
        RuntimeConnection.PreparedTargets<String> prepared =
            connection.prepareTargets(binding("internal", "external"));
        connection.bindTargets(prepared);
        assertThatThrownBy(() -> connection.prepareTargets(binding("other", "other")))
            .hasMessageContaining("cannot bind a direct target from state RUNNING");
        connection.beginStopping();
        try {
            connection.closeRoute();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        connection.invalidateDirectTarget();
        connection.completeStopping();
        assertThatThrownBy(connection::beginStartup)
            .hasMessageContaining("cannot transition from STOPPED to STARTING");

        RuntimeConnection<String> failed = new RuntimeConnection<>(
            Connection.connect(new Client("failed").api, server.api)
        );
        failed.beginStartup();
        failed.fail();
        assertThatThrownBy(() -> failed.resolve(failed.declaration().from()))
            .hasMessageContaining("state FAILED");
        assertThatThrownBy(failed::fail)
            .hasMessageContaining("cannot transition from FAILED to FAILED");
    }

    @Test
    void shouldPrepareIsolatedRoutedTargetsAndCloseThemInReverseOrder() {
        Client first = new Client("first");
        Client second = new Client("second");
        Server server = new Server();
        Connection<String> firstDeclaration = Connection.connect(first.api, server.api);
        Connection<String> secondDeclaration = Connection.connect(second.api, server.api);
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        AtomicReference<RuntimeConnectionRegistry> registryRef = new AtomicReference<>();
        List<String> receivedDirectTargets = new ArrayList<>();
        List<ConnectionId> cleanupOrder = new ArrayList<>();
        ConnectionRouting routing = ConnectionRouting.routed(
            String.class,
            (descriptor, directTarget) -> {
                receivedDirectTargets.add(directTarget.internal());
                String routeEndpoint = "route-secret-" + descriptor.sourceComponentId();
                return ConnectionRoute.routed(
                    binding(routeEndpoint, routeEndpoint + "-external"),
                    () -> {
                        RuntimeConnectionSnapshot snapshot =
                            registryRef.get().snapshot(descriptor.id());
                        assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPING);
                        assertThat(snapshot.directTargetAvailable()).isTrue();
                        assertThat(snapshot.consumerTargetAvailable()).isFalse();
                        cleanupOrder.add(descriptor.id());
                    }
                );
            }
        );
        RuntimeConnectionRegistry registry = registry(
            List.of(firstDeclaration, secondDeclaration),
            journal,
            routing
        );
        registryRef.set(registry);
        RuntimeConnectionSnapshot declared = registry.snapshot(firstDeclaration.id());

        registry.beginStartup();
        ComponentRuntime<Void> provider = ComponentRuntime.<Void>runtime()
            .provides(
                server.api,
                binding("direct-secret-internal", "direct-secret-external")
            )
            .build();
        var prepared = registry.prepareTargets(server, provider);
        registry.bindTargets(prepared);

        assertThat(receivedDirectTargets)
            .containsExactly("direct-secret-internal", "direct-secret-internal");
        assertThat(registry.resolve(first.api)).isEqualTo("route-secret-client-first");
        assertThat(registry.resolve(second.api)).isEqualTo("route-secret-client-second");
        assertThat(registry.connection(firstDeclaration.id()).directTarget().internal())
            .isEqualTo("direct-secret-internal");
        assertThat(registry.connection(firstDeclaration.id()).consumerTarget().internal())
            .isEqualTo("route-secret-client-first");
        assertThat(registry.snapshots())
            .allSatisfy(snapshot -> {
                assertThat(snapshot.routingMode()).isEqualTo(RoutingMode.ROUTED);
                assertThat(snapshot.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(snapshot.directTargetAvailable()).isTrue();
                assertThat(snapshot.consumerTargetAvailable()).isTrue();
            });
        assertThat(declared)
            .satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.DECLARED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });

        assertThat(registry.beginProviderCleanup(server)).isNull();
        assertThat(cleanupOrder)
            .containsExactly(secondDeclaration.id(), firstDeclaration.id());
        registry.completeProviderCleanup(server);

        assertThat(registry.snapshots())
            .allSatisfy(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        assertThat(new EnvironmentEventLog(journal, EnvironmentLogging.defaults())
            .snapshot()
            .content())
            .contains(
                "mode=ROUTED",
                "directTargetAvailable=true",
                "consumerTargetAvailable=true"
            )
            .doesNotContain(
                "direct-secret-internal",
                "direct-secret-external",
                "route-secret-client-first",
                "route-secret-client-second"
            );
    }

    @Test
    void shouldRollBackPreparedRoutesWhenLaterRouteCreationFails() {
        Client first = new Client("first");
        Client second = new Client("second");
        Client third = new Client("third");
        Server server = new Server();
        Connection<String> firstDeclaration = Connection.connect(first.api, server.api);
        Connection<String> secondDeclaration = Connection.connect(second.api, server.api);
        Connection<String> thirdDeclaration = Connection.connect(third.api, server.api);
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        IllegalStateException startupFailure =
            new IllegalStateException("route creation failed");
        IllegalStateException cleanupFailure =
            new IllegalStateException("route cleanup failed");
        AtomicInteger preparations = new AtomicInteger();
        List<ConnectionId> cleanupOrder = new ArrayList<>();
        ConnectionRouting routing = ConnectionRouting.routed(
            String.class,
            (descriptor, directTarget) -> {
                if (preparations.incrementAndGet() == 3) {
                    throw startupFailure;
                }
                return ConnectionRoute.routed(
                    binding("route-secret", "route-secret-external"),
                    () -> {
                        cleanupOrder.add(descriptor.id());
                        if (descriptor.id().equals(firstDeclaration.id())) {
                            throw cleanupFailure;
                        }
                    }
                );
            }
        );
        RuntimeConnectionRegistry registry = registry(
            List.of(firstDeclaration, secondDeclaration, thirdDeclaration),
            journal,
            routing
        );
        RuntimeBindings bindings = new RuntimeBindings(registry);
        ComponentRuntime<Void> provider = ComponentRuntime.<Void>runtime()
            .provides(server.api, binding("direct-secret", "direct-secret-external"))
            .build();
        registry.beginStartup();

        assertThatThrownBy(() -> bindings.attach(server, provider))
            .isSameAs(startupFailure)
            .satisfies(failure ->
                assertThat(failure.getSuppressed()).containsExactly(cleanupFailure)
            );

        assertThat(cleanupOrder)
            .containsExactly(secondDeclaration.id(), firstDeclaration.id());
        assertThat(registry.snapshots())
            .allSatisfy(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.FAILED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        assertThat(journal.snapshot().entries())
            .map(entry -> entry.event())
            .filteredOn(FailureEvent.ConnectionMaterialization.class::isInstance)
            .hasSize(3);
        assertThat(journal.snapshot().entries())
            .map(entry -> entry.event())
            .filteredOn(FailureEvent.ConnectionCleanup.class::isInstance)
            .hasSize(1);
        assertThat(new EnvironmentEventLog(journal, EnvironmentLogging.defaults())
            .snapshot()
            .content())
            .doesNotContain(
                "direct-secret",
                "direct-secret-external",
                "route-secret",
                "route-secret-external"
            );
    }

    @Test
    void shouldFailOneConnectionAndCloseItsRouteExactlyOnce() {
        Client client = new Client("cleanup");
        Server server = new Server();
        Connection<String> declaration = Connection.connect(client.api, server.api);
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        IllegalStateException cleanupFailure =
            new IllegalStateException("owned route cleanup failed");
        AtomicInteger cleanupCalls = new AtomicInteger();
        RuntimeConnectionRegistry registry = registry(
            List.of(declaration),
            journal,
            ConnectionRouting.routed(
                String.class,
                (descriptor, directTarget) -> ConnectionRoute.routed(
                    binding("route", "route-external"),
                    () -> {
                        cleanupCalls.incrementAndGet();
                        throw cleanupFailure;
                    }
                )
            )
        );
        registry.beginStartup();
        ComponentRuntime<Void> provider = ComponentRuntime.<Void>runtime()
            .provides(server.api, binding("direct", "direct-external"))
            .build();
        registry.bindTargets(registry.prepareTargets(server, provider));

        assertThat(registry.beginProviderCleanup(server)).isSameAs(cleanupFailure);
        assertThat(registry.stopRemaining()).isNull();
        registry.completeProviderCleanup(server);

        assertThat(cleanupCalls).hasValue(1);
        assertThat(registry.snapshot(declaration.id()))
            .satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.FAILED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        assertThat(journal.snapshot().entries())
            .map(entry -> entry.event())
            .filteredOn(FailureEvent.ConnectionCleanup.class::isInstance)
            .hasSize(1);
    }

    @Test
    void shouldMaterializeFlattenedDisplayCollisionsAsDistinctRuntimeConnections() {
        ComponentId unqualifiedId = ComponentId.component(
            ComponentType.of("client-a")
        );
        ComponentId qualifiedId = ComponentId.component(CLIENT, "a");
        Client unqualified = new Client(unqualifiedId);
        Client qualified = new Client(qualifiedId);
        Server server = new Server();
        Connection<String> unqualifiedDeclaration = Connection.connect(
            unqualified.api,
            server.api
        );
        Connection<String> qualifiedDeclaration = Connection.connect(
            qualified.api,
            server.api
        );
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);

        RuntimeConnectionRegistry registry = registry(
            List.of(unqualifiedDeclaration, qualifiedDeclaration),
            journal
        );

        assertThat(unqualified.id().toString()).isEqualTo(qualified.id().toString());
        assertThat(unqualifiedDeclaration.id())
            .isNotEqualTo(qualifiedDeclaration.id());
        assertThat(registry.snapshots())
            .extracting(RuntimeConnectionSnapshot::id)
            .containsExactly(
                unqualifiedDeclaration.id(),
                qualifiedDeclaration.id()
            )
            .doesNotHaveDuplicates();
        assertThat(registry.connection(unqualifiedDeclaration.id()).sourceComponentId())
            .isEqualTo(unqualifiedId);
        assertThat(registry.connection(qualifiedDeclaration.id()).sourceComponentId())
            .isEqualTo(qualifiedId);
        assertThat(journal.snapshot().entries())
            .map(entry -> entry.event())
            .filteredOn(ConnectionLifecycleEvent.class::isInstance)
            .map(ConnectionLifecycleEvent.class::cast)
            .extracting(event -> event.connection().id())
            .containsExactly(
                unqualifiedDeclaration.id(),
                qualifiedDeclaration.id()
            );
    }

    private static RuntimeConnectionRegistry registry(
        List<ConnectionRef> declarations,
        ScenarioJournal journal
    ) {
        return new RuntimeConnectionRegistry(
            declarations,
            new EnvironmentEventLog(journal, EnvironmentLogging.defaults())
        );
    }

    private static RuntimeConnectionRegistry registry(
        List<ConnectionRef> declarations,
        ScenarioJournal journal,
        ConnectionRouting routing
    ) {
        return new RuntimeConnectionRegistry(
            declarations,
            new EnvironmentEventLog(journal, EnvironmentLogging.defaults()),
            routing
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
        private final ComponentType type;
        private final RequiredPort<String> api;

        private Client(String qualifier) {
            this(ComponentId.component(CLIENT, qualifier));
        }

        private Client(ComponentId id) {
            super(
                id,
                new EmptyConfig(),
                Void.class,
                UNUSED
            );
            type = id.type();
            api = requires("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> api;

        private Server() {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, UNUSED);
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }
}
