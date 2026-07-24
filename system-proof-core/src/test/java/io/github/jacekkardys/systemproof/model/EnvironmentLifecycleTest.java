package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.driver.ComponentRuntime.runtime;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointAddress.address;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;

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
            .contains("Environment startup failed", "server cleanup failed", "component=server");
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
