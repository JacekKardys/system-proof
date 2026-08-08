package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.endpoint.EndpointBinding.binding;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

class ObservationStatusProviderConcurrencyTest {
    private static final Contract<String> API = contract("api", String.class);

    @Test
    void shouldSampleARealRoutedConnectionOutsideTheRuntimeMonitorAndCacheIt()
        throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        TestEnvironment environment = environment(() -> {
            if (providerCalls.incrementAndGet() == 2) {
                providerEntered.countDown();
                await(releaseProvider);
            }
            return EffectiveObservationStatus.ACTIVE;
        });
        environment.start();
        assertThat(providerCalls).hasValue(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<EnvironmentDiagnostics> diagnostics =
                CompletableFuture.supplyAsync(
                environment::diagnostics,
                executor
            );
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<EnvironmentState> concurrentState = CompletableFuture.supplyAsync(
                environment::state,
                executor
            );
            assertThat(concurrentState.get(2, TimeUnit.SECONDS))
                .isEqualTo(EnvironmentState.RUNNING);
            assertThat(CompletableFuture.supplyAsync(
                environment::runtimeConnections,
                executor
            ).get(2, TimeUnit.SECONDS)).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(snapshot.effectiveObservationStatus())
                    .isEqualTo(EffectiveObservationStatus.ACTIVE);
            });
            assertThat(providerCalls).hasValue(3);

            releaseProvider.countDown();
            assertThat(diagnostics.get(5, TimeUnit.SECONDS).content()).contains(
                "[STATE] environment=RUNNING",
                "effectiveObservationStatus=ACTIVE state=RUNNING"
            );
        } finally {
            releaseProvider.countDown();
        }

        assertThat(environment.state()).isEqualTo(EnvironmentState.RUNNING);
        assertThat(providerCalls).hasValue(3);

        environment.close();

        assertThat(environment.state()).isEqualTo(EnvironmentState.STOPPED);
        assertThat(environment.runtimeConnections()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPED);
            assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.INACTIVE);
        });
        assertThat(environment.diagnostics().content()).contains(
            "[STATE] environment=STOPPED",
            "effectiveObservationStatus=INACTIVE state=STOPPED"
        );
        assertThat(providerCalls).hasValue(3);
    }

    @Test
    void shouldPreserveAndRedactAThrowingObservationProviderAsThePrimaryFailure() {
        String secret = "observation-provider-message-canary";
        IllegalStateException providerFailure = new IllegalStateException(
            "provider failed with " + secret
        );
        AtomicInteger providerCalls = new AtomicInteger();
        TestEnvironment environment = environment(() -> {
            providerCalls.incrementAndGet();
            throw providerFailure;
        });

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause()).isSameAs(providerFailure);
        assertThat(providerCalls).hasValue(1);
        assertThat(thrown.getMessage()).doesNotContain(secret);
        assertThat(thrown.diagnostics().content())
            .contains("IllegalStateException")
            .doesNotContain(secret);
        assertThat(environment.diagnostics().content())
            .contains("IllegalStateException")
            .doesNotContain(secret);
        assertThat(environment.journalSnapshot().entries().toString())
            .doesNotContain(secret);

        environment.close();
        assertThat(environment.state()).isEqualTo(EnvironmentState.STOPPED);
        assertThat(providerCalls).hasValue(1);
    }

    private static TestEnvironment environment(ObservationStatusProvider statusProvider) {
        Server server = new Server();
        Client client = new Client();
        return new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api)
            .build((topology, logging) -> new TestEnvironment(
                topology,
                logging,
                ConnectionRouting.routed(
                    API,
                    ObservationRequirement.REQUIRED,
                    context -> ConnectionRoute.routed(
                        binding("routed-internal", "routed-external"),
                        statusProvider,
                        () -> {}
                    )
                )
            ));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release observation provider");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for observation provider", interrupted);
        }
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
        private final RequiredPort<String> api;

        private Client() {
            super(
                ComponentId.component(ComponentType.of("observation-client")),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .build()
            );
            api = requiresAtStartup(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> api;

        private Server() {
            super(
                ComponentId.component(ComponentType.of("observation-server")),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .provides(((Server) component).api, binding("direct", "direct-external"))
                    .build()
            );
            api = provides(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class TestEnvironment extends Environment {
        private TestEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing
        ) {
            super(topology, logging, routing);
        }

    }
}
