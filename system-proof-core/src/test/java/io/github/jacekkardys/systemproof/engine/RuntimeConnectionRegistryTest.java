package io.github.jacekkardys.systemproof.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.driver.ComponentRuntime.runtime;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Connection;
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
        var prepared = registry.prepareDirectTargets(server, provider);
        assertThat(prepared).hasSize(2);
        registry.bindDirectTargets(prepared);

        assertThat(registry.resolve(first.api)).isEqualTo("internal-secret-endpoint");
        assertThat(registry.resolve(second.api)).isEqualTo("internal-secret-endpoint");
        assertThat(registry.connection(firstDeclaration.id()).directTarget())
            .satisfies(target -> {
                assertThat(target.internal()).isEqualTo("internal-secret-endpoint");
                assertThat(target.external()).isEqualTo("external-secret-endpoint");
            });
        assertThat(registry.snapshots())
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(connection.routingMode()).isEqualTo(RoutingMode.DIRECT);
                assertThat(connection.directTargetAvailable()).isTrue();
            });
        assertThat(declared)
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.DECLARED);
                assertThat(connection.directTargetAvailable()).isFalse();
            });
        assertThatThrownBy(() -> registry.prepareDirectTargets(server, provider))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot bind a direct target from state RUNNING");

        registry.beginProviderCleanup(server);
        assertThatThrownBy(() -> registry.resolve(first.api))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(firstDeclaration.id().toString(), "STOPPING");
        registry.completeProviderCleanup(server);

        assertThat(registry.snapshots())
            .allSatisfy(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(connection.directTargetAvailable()).isFalse();
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
                "bindDirectTarget",
                "beginStopping",
                "completeStopping",
                "fail"
            );
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
        connection.bindDirectTarget(binding("internal", "external"));
        assertThatThrownBy(() -> connection.bindDirectTarget(binding("other", "other")))
            .hasMessageContaining("cannot bind a direct target from state RUNNING");
        connection.beginStopping();
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

        @Override
        protected ComponentType componentType() {
            return type;
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> api;

        private Server() {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, UNUSED);
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return SERVER;
        }
    }
}
