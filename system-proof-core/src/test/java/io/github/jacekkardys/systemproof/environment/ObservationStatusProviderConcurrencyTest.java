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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.control.SemanticControls;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.control.SemanticInteractionSelector;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuard;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardFailure;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardSpec;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardState;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.ForwardingDecision;
import io.github.jacekkardys.systemproof.observation.ForwardingPermit;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.observation.RecordedInteraction;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.journal.SemanticHoldEvent;
import io.github.jacekkardys.systemproof.journal.SemanticPredecessorGuardEvent;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
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
    void shouldRejectArmWhenRequiredObservationFailureLinearizesAfterStaleActiveSample()
        throws Exception {
        BlockingControlRouteProvider provider = new BlockingControlRouteProvider();
        TestEnvironment environment = environment(provider);
        SemanticControls controls = environment.controls();
        environment.start();
        ConnectionId connectionId = environment.connectionId();
        provider.blockNext(connectionId);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<SemanticHold> arm = CompletableFuture.supplyAsync(
                () -> controls.arm(selector(connectionId), CONTROL_TIMEOUT),
                executor
            );
            assertThat(provider.awaitBlocked()).isTrue();

            CompletableFuture<Void> failure = CompletableFuture.runAsync(
                () -> provider.failRequiredObservation(connectionId),
                executor
            );
            failure.get(2, TimeUnit.SECONDS);
            assertThat(environment.state()).isEqualTo(EnvironmentState.RUNNING);
            assertThat(environment.runtimeConnection(connectionId))
                .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                    .isEqualTo(EffectiveObservationStatus.ACTIVE));

            provider.release();
            assertTerminalObservationFailure(arm);
        } finally {
            provider.release();
        }

        assertThat(holdEvents(environment)).isEmpty();
        assertThat(environment.runtimeConnection(connectionId))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED));
        assertThat(provider.maximumConcurrentCalls()).isOne();
        environment.close();
    }

    @Test
    void shouldRejectGuardWhenRequiredObservationFailureLinearizesAfterStaleActiveSample()
        throws Exception {
        BlockingControlRouteProvider provider = new BlockingControlRouteProvider();
        TestEnvironment environment = environment(provider);
        SemanticControls controls = environment.controls();
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.start();
        ConnectionId connectionId = environment.connectionId();
        provider.blockNext(connectionId);
        SemanticPredecessorGuardSpec specification = guardSpecification(
            connectionId,
            subject
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<SemanticPredecessorGuard> guard =
                CompletableFuture.supplyAsync(
                    () -> controls.guard(specification),
                    executor
                );
            assertThat(provider.awaitBlocked()).isTrue();

            CompletableFuture<Void> failure = CompletableFuture.runAsync(
                () -> provider.failRequiredObservation(connectionId),
                executor
            );
            failure.get(2, TimeUnit.SECONDS);

            provider.release();
            assertTerminalObservationFailure(guard);
        } finally {
            provider.release();
        }

        assertThat(guardEvents(environment)).isEmpty();
        assertThat(environment.runtimeConnection(connectionId))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED));
        assertThat(provider.maximumConcurrentCalls()).isOne();
        environment.close();
    }

    @Test
    void shouldFailAnAlreadyAuthorizedGuardBeforeItsForwardedReport()
        throws Exception {
        BlockingControlRouteProvider provider = new BlockingControlRouteProvider();
        TestEnvironment environment = environment(provider);
        SemanticControls controls = environment.controls();
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.start();
        ConnectionId connectionId = environment.connectionId();
        SemanticPredecessorGuard guard = controls.guard(
            guardSpecification(connectionId, subject)
        );
        InteractionSession session = provider.observations(connectionId).openSession();
        RecordedInteraction predecessor = correlatedInteraction(
            environment,
            session,
            subject,
            "predecessor",
            1
        );
        assertForwarded(provider.coordinator().permit(predecessor));
        RecordedInteraction successor = correlatedInteraction(
            environment,
            session,
            subject,
            "successor",
            2
        );
        ForwardingPermit successorPermit = provider.coordinator().permit(successor);

        assertThat(successorPermit.awaitDecision()).isEqualTo(ForwardingDecision.FORWARD);
        assertThat(guard.state()).isEqualTo(
            SemanticPredecessorGuardState.SUCCESSOR_AUTHORIZED
        );

        provider.failRequiredObservation(connectionId);

        assertThat(guard.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
            .isEqualTo(SemanticPredecessorGuardState.FAILED);
        successorPermit.forwarded();
        assertThat(guard.state()).isEqualTo(SemanticPredecessorGuardState.FAILED);
        assertThat(guardEvents(environment).stream()
            .filter(event -> event.guardRef().equals(guard.ref()))
            .filter(event -> event.kind() == SemanticPredecessorGuardEvent.Kind.RELATION))
            .isEmpty();
        assertThat(guardEvents(environment).stream()
            .filter(event -> event.guardRef().equals(guard.ref()))
            .filter(event -> event.failure()
                .filter(SemanticPredecessorGuardFailure.REQUIRED_OBSERVATION_FAILURE::equals)
                .isPresent()))
            .hasSize(1);

        environment.close();
    }

    @Test
    void shouldKeepRequiredObservationFailureScopedToOneConnection() {
        BlockingControlRouteProvider provider = new BlockingControlRouteProvider();
        TestEnvironment environment = twoConnectionEnvironment(provider);
        SemanticControls controls = environment.controls();
        environment.start();
        ConnectionId failed = environment.connectionId(0);
        ConnectionId isolated = environment.connectionId(1);

        provider.failRequiredObservation(failed);

        SemanticHold hold = controls.arm(selector(isolated), CONTROL_TIMEOUT);
        assertThat(hold.state()).isEqualTo(SemanticHoldState.ARMED);
        assertThatThrownBy(() -> controls.arm(selector(failed), CONTROL_TIMEOUT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not currently have active semantic-control capability");
        assertThat(environment.runtimeConnection(failed))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED));
        assertThat(environment.runtimeConnection(isolated))
            .satisfies(snapshot -> assertThat(snapshot.effectiveObservationStatus())
                .isEqualTo(EffectiveObservationStatus.ACTIVE));

        environment.close();
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
        return environment(new ControlRouteProvider(statusProvider), dependentStart);
    }

    private static TestEnvironment environment(
        ConnectionRouteProvider<String> routeProvider
    ) {
        return environment(routeProvider, () -> {});
    }

    private static TestEnvironment environment(
        ConnectionRouteProvider<String> routeProvider,
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
                    routeProvider
                )
            ));
    }

    private static TestEnvironment twoConnectionEnvironment(
        ConnectionRouteProvider<String> routeProvider
    ) {
        DualServer server = new DualServer();
        DualClient client = new DualClient();
        return new EnvironmentBuilder()
            .components(client, server)
            .connect(client.first, server.first)
            .connect(client.second, server.second)
            .build((topology, logging) -> new TestEnvironment(
                topology,
                logging,
                ConnectionRouting.routed(API, CONTROL_PROFILE, routeProvider)
            ));
    }

    private static SemanticInteractionSelector<String> selector(
        TestEnvironment environment
    ) {
        return selector(environment.connectionId());
    }

    private static SemanticInteractionSelector<String> selector(
        ConnectionId connectionId
    ) {
        return selector(connectionId, ignored -> true);
    }

    private static SemanticInteractionSelector<String> selector(
        ConnectionId connectionId,
        Predicate<String> matcher
    ) {
        return matching(
            connectionId,
            FlowDirection.CONSUMER_TO_PROVIDER,
            TextCodec.INSTANCE,
            matcher
        );
    }

    private static SemanticPredecessorGuardSpec guardSpecification(
        ConnectionId connectionId,
        ProofSubjectRef subject
    ) {
        return SemanticPredecessorGuardSpec.requiring(
            subject,
            confirmed(selector(connectionId, "predecessor"::equals).forSubject(subject)),
            selector(connectionId, "successor"::equals).forSubject(subject),
            CONTROL_TIMEOUT
        );
    }

    private static RecordedInteraction correlatedInteraction(
        TestEnvironment environment,
        InteractionSession session,
        ProofSubjectRef subject,
        String evidence,
        int keySeed
    ) {
        CorrelationKey key = correlationKey(keySeed);
        environment.proofSubjects().arm(subject, key);
        RecordedInteraction interaction = session.record(
            FlowDirection.CONSUMER_TO_PROVIDER,
            TextCodec.INSTANCE,
            evidence
        );
        session.correlate(
            interaction.interactionRef(),
            CorrelationContribution.capture(key, TextCodec.INSTANCE, "reference-" + keySeed)
        );
        return interaction;
    }

    private static CorrelationKey correlationKey(int seed) {
        byte[] digest = new byte[16];
        Arrays.fill(digest, (byte) seed);
        return CorrelationKey.ofDigest(
            new CorrelationKeySchema("system-proof-test", "observation-race", 1),
            digest
        );
    }

    private static void assertForwarded(ForwardingPermit permit) throws Exception {
        assertThat(permit.awaitDecision()).isEqualTo(ForwardingDecision.FORWARD);
        permit.forwarded();
    }

    private static void assertTerminalObservationFailure(
        CompletableFuture<?> control
    ) throws Exception {
        ExecutionException failure = catchThrowableOfType(
            () -> control.get(5, TimeUnit.SECONDS),
            ExecutionException.class
        );
        assertThat(failure.getCause())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("terminal required-observation failure")
            .hasNoCause();
    }

    private static List<SemanticHoldEvent> holdEvents(TestEnvironment environment) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(SemanticHoldEvent.class::isInstance)
            .map(SemanticHoldEvent.class::cast)
            .toList();
    }

    private static List<SemanticPredecessorGuardEvent> guardEvents(
        TestEnvironment environment
    ) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(SemanticPredecessorGuardEvent.class::isInstance)
            .map(SemanticPredecessorGuardEvent.class::cast)
            .toList();
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

    private static final class DualClient extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<String> first;
        private final RequiredPort<String> second;

        private DualClient() {
            super(
                ComponentId.component(ComponentType.of("dual-observation-client")),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
            first = requiresAtStartup(
                this,
                "first",
                API,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
            second = requiresAtStartup(
                this,
                "second",
                API,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
        }
    }

    private static final class DualServer extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> first;
        private final ProvidedPort<String> second;

        private DualServer() {
            super(
                ComponentId.component(ComponentType.of("dual-observation-server")),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .provides(
                        ((DualServer) component).first,
                        binding("first-direct", "first-direct-external")
                    )
                    .provides(
                        ((DualServer) component).second,
                        binding("second-direct", "second-direct-external")
                    )
                    .build()
            );
            first = provides(
                this,
                "first",
                API,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
            second = provides(
                this,
                "second",
                API,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
        }
    }

    private static final class BlockingControlRouteProvider
        implements ConnectionRouteProvider<String>, SemanticControlRouteCapability {
        private final Map<ConnectionId, AtomicReference<EffectiveObservationStatus>> statuses =
            new ConcurrentHashMap<>();
        private final Map<ConnectionId, ConnectionObservations> observations =
            new ConcurrentHashMap<>();
        private final AtomicReference<InteractionDecisionCoordinator> coordinator =
            new AtomicReference<>();
        private final AtomicReference<ConnectionId> blockedConnection =
            new AtomicReference<>();
        private final AtomicBoolean blockPending = new AtomicBoolean();
        private final CountDownLatch providerBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseProvider = new CountDownLatch(1);
        private final AtomicInteger concurrentCalls = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();

        @Override
        public ConnectionRoute<String> prepare(ConnectionRouteContext<String> context) {
            ConnectionId connectionId = context.connection().id();
            statuses.put(
                connectionId,
                new AtomicReference<>(EffectiveObservationStatus.ACTIVE)
            );
            observations.put(connectionId, context.observations());
            InteractionDecisionCoordinator captured = context.coordinator();
            InteractionDecisionCoordinator existing = coordinator.get();
            if (existing == null) {
                coordinator.compareAndSet(null, captured);
                existing = coordinator.get();
            }
            if (existing != captured) {
                throw new IllegalStateException(
                    "Routed connections did not receive one environment coordinator"
                );
            }
            return ConnectionRoute.routed(
                binding("routed-internal", "routed-external"),
                () -> sample(connectionId),
                new ControlRouteResource()
            );
        }

        private EffectiveObservationStatus sample(ConnectionId connectionId) {
            int concurrent = concurrentCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(concurrent, Math::max);
            try {
                EffectiveObservationStatus captured = status(connectionId).get();
                if (connectionId.equals(blockedConnection.get())
                    && blockPending.compareAndSet(true, false)) {
                    providerBlocked.countDown();
                    await(releaseProvider);
                }
                return captured;
            } finally {
                concurrentCalls.decrementAndGet();
            }
        }

        private void blockNext(ConnectionId connectionId) {
            blockedConnection.set(connectionId);
            blockPending.set(true);
        }

        private boolean awaitBlocked() throws InterruptedException {
            return providerBlocked.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            releaseProvider.countDown();
        }

        private void failRequiredObservation(ConnectionId connectionId) {
            status(connectionId).set(EffectiveObservationStatus.FAILED);
            coordinator().observationFailed(connectionId);
        }

        private AtomicReference<EffectiveObservationStatus> status(
            ConnectionId connectionId
        ) {
            return java.util.Objects.requireNonNull(
                statuses.get(connectionId),
                "Connection status was not prepared"
            );
        }

        private InteractionDecisionCoordinator coordinator() {
            return java.util.Objects.requireNonNull(
                coordinator.get(),
                "Coordinator was not captured"
            );
        }

        private ConnectionObservations observations(ConnectionId connectionId) {
            return java.util.Objects.requireNonNull(
                observations.get(connectionId),
                "Connection observations were not captured"
            );
        }

        private int maximumConcurrentCalls() {
            return maximumConcurrentCalls.get();
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
            return connectionId(0);
        }

        private ConnectionId connectionId(int index) {
            return connections().get(index).id();
        }
    }
}
