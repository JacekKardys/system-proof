package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.driver.ComponentRuntime.runtime;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointAddress.address;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
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
        Environment environment = Environment.environment()
            .components(client, server)
            .connect(client.api, server.api)
            .build();

        assertThatThrownBy(() -> environment.operations(client))
            .isInstanceOf(ComponentLifecycleException.class)
            .hasMessageContaining("client", "DECLARED", "RUNNING");

        assertThat(environment.start()).isSameAs(environment);
        assertThat(environment.operations(client)).isEqualTo("http://server.test:8080/api");
        assertThat(environment.componentState(client)).isEqualTo(ComponentState.RUNNING);

        environment.close();

        assertThat(cleanup).containsExactly("client", "server");
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
        assertThat(events(environment, DiagnosticEvent.class))
            .filteredOn(event ->
                event.subject() instanceof DiagnosticEvent.ConnectionSubject
            )
            .singleElement()
            .satisfies(event -> {
                assertThat(event.subject()).isEqualTo(
                    new DiagnosticEvent.ConnectionSubject(
                        environment.connections().getFirst().id()
                    )
                );
                assertThat(event.level()).isEqualTo(LogLevel.INFO);
                assertThat(event.message()).contains("client.api -> server.api");
            });
    }

    @Test
    void shouldCleanupPartialStartupAndSuppressCleanupFailureOnThePrimaryCause() {
        IllegalStateException cleanupFailure = new IllegalStateException("server cleanup failed");
        Server server = new Server((component, context) ->
            io.github.jacekkardys.systemproof.driver.ComponentRuntime.<Void>runtime(() -> { throw cleanupFailure; })
                .provides(((Server) component).api,
                    binding(
                        new ApiEndpoint(address("http", "server.test", 8080).value()),
                        new ApiEndpoint(address("http", "localhost", 49152).value())
                    ))
                .build()
        );
        Client client = new Client((component, context) -> {
            throw new IllegalStateException("client failed");
        });
        Environment environment = Environment.environment()
            .components(client, server)
            .connect(client.api, server.api)
            .build();

        assertThatThrownBy(environment::start)
            .isInstanceOfSatisfying(EnvironmentStartException.class, failure -> {
                assertThat(failure.getCause()).hasMessage("client failed");
                assertThat(failure.getCause().getSuppressed()).containsExactly(cleanupFailure);
            });
        assertThat(environment.diagnostics().content())
            .contains(
                "Environment startup failed",
                "Component startup failed",
                "client failed",
                "server cleanup failed",
                "component=server",
                "state=FAILED"
            );
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
        Environment environment = Environment.environment()
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
        Environment environment = Environment.environment()
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
        Environment environment = Environment.environment()
            .components(component, dependency)
            .connect(component.api, dependency.api)
            .build();

        environment.start();
        assertThat(environment.operations(component)).isEqualTo("remote");
        environment.close();
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
            super(ComponentId.component(CLIENT), new EmptyConfig(), String.class, driver);
            api = requiresAtStartup("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return CLIENT;
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<ApiEndpoint> api;

        private Server(ComponentDriver<EmptyConfig, Void> driver) {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, driver);
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

        @Override
        protected ComponentType componentType() {
            return SERVER;
        }
    }
}
