package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.control.SemanticInteractionSelector.matching;
import static io.github.jacekkardys.systemproof.control.SemanticPredecessorRequirement.confirmed;
import static io.github.jacekkardys.systemproof.endpoint.EndpointBinding.binding;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability.SEMANTIC_CONTROL;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.control.SemanticControls;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.control.SemanticInteractionSelector;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardSpec;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

class ObservationStatusProviderConcurrencyTest {
    private static final Contract<String> API = contract("api", String.class);
    private static final EvidenceSchemaId EVIDENCE_SCHEMA = new EvidenceSchemaId(
        "system-proof-test",
        "observation-refresh",
        1
    );
    private static final RequiredObservationProfile CONTROL_PROFILE =
        new RequiredObservationProfile(
            EVIDENCE_SCHEMA,
            Optional.empty(),
            Set.of(SEMANTIC_CONTROL),
            Set.of()
        );
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldSingleFlightRefreshAndServeCachedSnapshotsWhileProviderIsBlocked()
        throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger concurrentCalls = new AtomicInteger();
        AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        AtomicInteger dependentStarts = new AtomicInteger();
        TestEnvironment environment = environment(
            () -> {
                int concurrent = concurrentCalls.incrementAndGet();
                maximumConcurrentCalls.accumulateAndGet(concurrent, Math::max);
                try {
                    if (providerCalls.incrementAndGet() == 2) {
                        providerEntered.countDown();
                        await(releaseProvider);
                    }
                    return EffectiveObservationStatus.ACTIVE;
                } finally {
                    concurrentCalls.decrementAndGet();
                }
            },
            () -> {
                assertThat(providerCalls).hasValue(1);
                dependentStarts.incrementAndGet();
            }
        );
        SemanticControls retainedControls = environment.controls();
        environment.start();

        assertThat(providerCalls).hasValue(1);
        assertThat(dependentStarts).hasValue(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<EnvironmentDiagnostics> diagnostics =
                CompletableFuture.supplyAsync(environment::diagnostics, executor);
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(CompletableFuture.supplyAsync(
                environment::runtimeConnections,
                executor
            ).get(2, TimeUnit.SECONDS)).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(snapshot.effectiveObservationStatus())
                    .isEqualTo(EffectiveObservationStatus.ACTIVE);
            });
            assertThat(environment.runtimeConnection(environment.connectionId()))
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(ConnectionState.RUNNING);
                    assertThat(snapshot.effectiveObservationStatus())
                        .isEqualTo(EffectiveObservationStatus.ACTIVE);
                });
            assertThat(environment.state()).isEqualTo(EnvironmentState.RUNNING);
            assertThat(environment.controls()).isSameAs(retainedControls);
            assertThat(providerCalls).hasValue(2);
            assertThat(maximumConcurrentCalls).hasValue(1);
            assertThatThrownBy(() -> retainedControls.arm(
                selector(environment),
                CONTROL_TIMEOUT
            )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Fresh observation status is unavailable while a refresh is in progress");
            assertThat(providerCalls).hasValue(2);

            CompletableFuture<Void> close = CompletableFuture.runAsync(
                environment::close,
                executor
            );
            close.get(2, TimeUnit.SECONDS);
            assertThat(environment.state()).isEqualTo(EnvironmentState.STOPPED);

            releaseProvider.countDown();
            assertThat(diagnostics.get(5, TimeUnit.SECONDS).content()).contains(
                "[STATE] environment=STOPPED",
                "effectiveObservationStatus=INACTIVE state=STOPPED"
            );
        } finally {
            releaseProvider.countDown();
            environment.close();
        }

        assertThat(providerCalls).hasValue(2);
        assertThat(maximumConcurrentCalls).hasValue(1);
        assertThat(environment.runtimeConnections()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPED);
            assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.INACTIVE);
        });
    }

    @Test
    void shouldRefreshRetainedControlsAndNeverReactivateFailedCapability() {
        AtomicReference<EffectiveObservationStatus> status = new AtomicReference<>(
            EffectiveObservationStatus.ACTIVE
        );
        AtomicInteger providerCalls = new AtomicInteger();
        TestEnvironment environment = environment(() -> {
            providerCalls.incrementAndGet();
            return status.get();
        });
        SemanticControls retainedControls = environment.controls();
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.start();

        status.set(EffectiveObservationStatus.FAILED);
        assertThatThrownBy(() -> retainedControls.arm(
            selector(environment),
            CONTROL_TIMEOUT
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not currently have active semantic-control capability");

        status.set(EffectiveObservationStatus.ACTIVE);
        SemanticInteractionSelector<String> predecessor = selector(environment)
            .forSubject(subject);
        SemanticInteractionSelector<String> successor = selector(environment)
            .forSubject(subject);
        assertThatThrownBy(() -> retainedControls.guard(
            SemanticPredecessorGuardSpec.requiring(
                subject,
                confirmed(predecessor),
                successor,
                CONTROL_TIMEOUT
            )
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not currently have active semantic-control capability");

        assertThat(environment.runtimeConnection(environment.connectionId()))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED));
        assertThat(providerCalls).hasValue(4);

        environment.close();
        assertThat(environment.runtimeConnection(environment.connectionId()))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED));
        assertThat(providerCalls).hasValue(4);
    }

    @Test
    void shouldKeepDynamicProviderFailureSecretSafeAndFailClosed() {
        String secret = "dynamic-observation-provider-message-canary";
        IllegalStateException providerFailure = new IllegalStateException(
            "dynamic provider failed with " + secret
        );
        AtomicInteger providerCalls = new AtomicInteger();
        TestEnvironment environment = environment(() -> {
            if (providerCalls.incrementAndGet() == 1) {
                return EffectiveObservationStatus.ACTIVE;
            }
            throw providerFailure;
        });
        SemanticControls controls = environment.controls();
        environment.start();

        IllegalStateException thrown = catchThrowableOfType(
            () -> controls.arm(selector(environment), CONTROL_TIMEOUT),
            IllegalStateException.class
        );

        assertThat(thrown)
            .hasMessageContaining("does not currently have active semantic-control capability")
            .hasMessageNotContaining(secret)
            .hasNoCause();
        assertThat(environment.diagnostics().content())
            .contains("effectiveObservationStatus=FAILED")
            .doesNotContain(secret);
        assertThat(environment.journalSnapshot().entries().toString())
            .doesNotContain(secret);
        assertThat(providerCalls).hasValue(3);

        environment.close();
        assertThat(providerCalls).hasValue(3);
    }

    @Test
    void shouldPreservePreStartArmingAndSampleBeforeTheDependentStarts() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger dependentStarts = new AtomicInteger();
        TestEnvironment environment = environment(
            () -> {
                providerCalls.incrementAndGet();
                return EffectiveObservationStatus.ACTIVE;
            },
            () -> {
                assertThat(providerCalls).hasValue(1);
                dependentStarts.incrementAndGet();
            }
        );
        SemanticHold hold = environment.controls().arm(
            selector(environment),
            CONTROL_TIMEOUT
        );

        assertThat(hold.state()).isEqualTo(SemanticHoldState.ARMED);
        assertThat(providerCalls).hasValue(0);

        environment.start();

        assertThat(providerCalls).hasValue(1);
        assertThat(dependentStarts).hasValue(1);
        assertThat(environment.runtimeConnection(environment.connectionId()))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.ACTIVE));
        assertThat(providerCalls).hasValue(2);

        environment.close();
    }

    @Test
    void shouldPreserveAndRedactAThrowingStartupProviderAsThePrimaryFailure() {
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
        return environment(statusProvider, () -> {});
    }

    private static TestEnvironment environment(
        ObservationStatusProvider statusProvider,
        Runnable dependentStart
    ) {
        Server server = new Server();
        Client client = new Client(dependentStart);
        return new EnvironmentBuilder()
            .components(client, server)
            .connect(client.api, server.api)
            .build((topology, logging) -> new TestEnvironment(
                topology,
                logging,
                ConnectionRouting.routed(
                    API,
                    CONTROL_PROFILE,
                    new ControlRouteProvider(statusProvider)
                )
            ));
    }

    private static SemanticInteractionSelector<String> selector(
        TestEnvironment environment
    ) {
        return matching(
            environment.connectionId(),
            FlowDirection.CONSUMER_TO_PROVIDER,
            TextCodec.INSTANCE,
            ignored -> true
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release observation provider");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                "Interrupted while waiting for observation provider",
                interrupted
            );
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

    private enum TextCodec implements EvidenceCodec<String> {
        INSTANCE;

        @Override
        public EvidenceSchemaId schemaId() {
            return EVIDENCE_SCHEMA;
        }

        @Override
        public byte[] encode(String evidence) {
            return evidence.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] encodedEvidence) {
            return new String(encodedEvidence, StandardCharsets.UTF_8);
        }
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class Client extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<String> api;

        private Client(Runnable dependentStart) {
            super(
                ComponentId.component(ComponentType.of("observation-client")),
                new EmptyConfig(),
                Void.class,
                (component, context) -> {
                    dependentStart.run();
                    return ComponentRuntime.<Void>runtime().build();
                }
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

    private static final class ControlRouteProvider
        implements ConnectionRouteProvider<String>, SemanticControlRouteCapability {
        private final ObservationStatusProvider statusProvider;

        private ControlRouteProvider(ObservationStatusProvider statusProvider) {
            this.statusProvider = statusProvider;
        }

        @Override
        public ConnectionRoute<String> prepare(ConnectionRouteContext<String> context) {
            return ConnectionRoute.routed(
                binding("routed-internal", "routed-external"),
                statusProvider,
                new ControlRouteResource()
            );
        }
    }

    private static final class ControlRouteResource
        implements AutoCloseable, SemanticControlRouteCapability {
        @Override
        public void close() {}
    }

    private static final class TestEnvironment extends Environment {
        private TestEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing
        ) {
            super(topology, logging, routing);
        }

        private ConnectionId connectionId() {
            return connections().getFirst().id();
        }
    }
}
