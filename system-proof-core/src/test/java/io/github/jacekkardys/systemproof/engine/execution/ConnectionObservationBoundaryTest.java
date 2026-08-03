package io.github.jacekkardys.systemproof.engine.execution;

import static io.github.jacekkardys.systemproof.construction.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.construction.ComponentPortFactory.provides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.topology.Contract.contract;
import static io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding.binding;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.proof.ProofSubjects;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentType;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;

class ConnectionObservationBoundaryTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<ApiEndpoint> API = contract("api", ApiEndpoint.class);

    @Test
    void shouldPublishTypedCorrelationOnlyForAnInteractionRecordedByTheSameSession() {
        Client client = new Client("correlated");
        Server server = new Server("internal", "external");
        CorrelationKey key = CorrelationKey.ofDigest(
            new CorrelationKeySchema("system-proof-test", "operation", 1),
            new byte[32]
        );
        AtomicReference<InteractionRef> observed = new AtomicReference<>();
        RoutedEnvironment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(client, server)
                .connect(client.api, server.api),
            context -> {
                InteractionSession session = context.observations().openSession();
                CorrelationContribution<MutableInteractionEvidence> contribution =
                    CorrelationContribution.capture(
                        key,
                        MutableInteractionEvidence.codec(),
                        evidence("native-reference")
                    );
                InteractionRef unrecorded = new InteractionRef(
                    new SessionId(context.connection().id(), 99),
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    1
                );
                assertThatThrownBy(() -> session.correlate(unrecorded, contribution))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                        "Interaction reference does not belong to this physical session"
                    );

                InteractionRef interactionRef = session.observe(
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    MutableInteractionEvidence.codec(),
                    evidence("observed")
                );
                session.correlate(interactionRef, contribution);
                observed.set(interactionRef);
                return ConnectionRoute.routed(context.directTarget());
            }
        );
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.proofSubjects().arm(subject, key);

        try {
            environment.start();

            assertThat(environment.proofSubjects().correlation(
                subject,
                key,
                MutableInteractionEvidence.codec()
            )).isInstanceOfSatisfying(
                CorrelationResult.Unique.class,
                result -> assertThat(result.interactionRef())
                    .isEqualTo(observed.get())
            );
            List<ScenarioEvent> events = environment.journalSnapshot().entries().stream()
                .map(entry -> entry.event())
                .toList();
            int observationIndex = indexOf(events, InteractionObservationEvent.class);
            int correlationIndex = indexOf(events, CorrelationCandidateEvent.class);
            assertThat(observationIndex).isGreaterThanOrEqualTo(0);
            assertThat(correlationIndex).isGreaterThan(observationIndex);
        } finally {
            environment.close();
        }
    }

    @Test
    void shouldAssignConnectionSessionDirectionAndOrdinalWithoutCallerSuppliedIdentity() {
        String directInternal = "direct-internal-secret";
        String directExternal = "direct-external-secret";
        MutableInteractionEvidence source = new MutableInteractionEvidence(
            "sensitive-binary".getBytes(StandardCharsets.UTF_8),
            new ArrayList<>(List.of("original-attribute"))
        );
        AtomicReference<String> firstClientDiagnostics = new AtomicReference<>();
        Client first = new Client("first", (component, context) -> {
            firstClientDiagnostics.set(context.componentEvents(component));
            return ComponentRuntime.<Void>runtime().build();
        });
        Client second = new Client("second");
        Server server = new Server(directInternal, directExternal);
        ConnectionId firstConnection = ConnectionId.between(first.api, server.api);
        ConnectionId secondConnection = ConnectionId.between(second.api, server.api);
        Map<ConnectionId, ConnectionObservations> capabilities =
            new ConcurrentHashMap<>();
        List<InteractionRef> assigned = new CopyOnWriteArrayList<>();

        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(first, second, server)
                .connect(first.api, server.api)
                .connect(second.api, server.api),
            context -> {
                ConnectionId connectionId = context.connection().id();
                capabilities.put(connectionId, context.observations());
                InteractionSession firstSession = context.observations().openSession();
                if (connectionId.equals(firstConnection)) {
                    assigned.add(firstSession.observe(
                        FlowDirection.CONSUMER_TO_PROVIDER,
                        MutableInteractionEvidence.codec(),
                        source
                    ));
                    assigned.add(firstSession.observe(
                        FlowDirection.CONSUMER_TO_PROVIDER,
                        MutableInteractionEvidence.codec(),
                        evidence("first-request-2")
                    ));
                    assigned.add(firstSession.observe(
                        FlowDirection.PROVIDER_TO_CONSUMER,
                        MutableInteractionEvidence.codec(),
                        evidence("first-response")
                    ));
                    assigned.add(context.observations().openSession().observe(
                        FlowDirection.CONSUMER_TO_PROVIDER,
                        MutableInteractionEvidence.codec(),
                        evidence("first-reconnect")
                    ));
                } else {
                    assigned.add(firstSession.observe(
                        FlowDirection.CONSUMER_TO_PROVIDER,
                        MutableInteractionEvidence.codec(),
                        evidence("second-request")
                    ));
                }
                return ConnectionRoute.routed(context.directTarget());
            }
        ).start();

        ScenarioJournalSnapshot captured = environment.journalSnapshot();
        source.payload()[0] = 'X';
        source.attributes().set(0, "mutated-attribute");
        List<InteractionObservationEvent> events =
            events(captured, InteractionObservationEvent.class);
        List<InteractionRef> references = events.stream()
            .map(InteractionObservationEvent::interactionRef)
            .toList();
        InteractionObservationEvent firstEvent = events.stream()
            .filter(event -> event.interactionRef().equals(assigned.getFirst()))
            .findFirst()
            .orElseThrow();

        assertThat(capabilities).containsOnlyKeys(firstConnection, secondConnection);
        assertThat(references).containsExactlyElementsOf(assigned);
        assertThat(new HashSet<>(references)).hasSameSizeAs(references);
        assertThat(references)
            .extracting(InteractionRef::connectionId)
            .containsExactly(
                firstConnection,
                firstConnection,
                firstConnection,
                firstConnection,
                secondConnection
            );

        InteractionRef firstRequest = assigned.get(0);
        InteractionRef secondRequest = assigned.get(1);
        InteractionRef response = assigned.get(2);
        InteractionRef reconnect = assigned.get(3);
        InteractionRef otherConnection = assigned.get(4);
        assertThat(firstRequest.sessionId()).isEqualTo(secondRequest.sessionId());
        assertThat(firstRequest.sessionId()).isEqualTo(response.sessionId());
        assertThat(reconnect.sessionId()).isNotEqualTo(firstRequest.sessionId());
        assertThat(firstRequest.sessionId().localValue()).isEqualTo(1L);
        assertThat(reconnect.sessionId().localValue()).isEqualTo(2L);
        assertThat(otherConnection.sessionId().localValue()).isEqualTo(1L);
        assertThat(otherConnection.sessionId()).isNotEqualTo(firstRequest.sessionId());
        assertThat(firstRequest.ordinal()).isEqualTo(1L);
        assertThat(secondRequest.ordinal()).isEqualTo(2L);
        assertThat(response.ordinal()).isEqualTo(1L);
        assertThat(reconnect.ordinal()).isEqualTo(1L);
        assertThat(otherConnection.ordinal()).isEqualTo(1L);

        MutableInteractionEvidence decoded =
            firstEvent.evidence().decode(MutableInteractionEvidence.codec());
        assertThat(decoded.payload())
            .containsExactly("sensitive-binary".getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.attributes()).containsExactly("original-attribute");
        decoded.payload()[0] = 'Y';
        decoded.attributes().set(0, "decoded-mutation");
        MutableInteractionEvidence decodedAgain =
            firstEvent.evidence().decode(MutableInteractionEvidence.codec());
        assertThat(decodedAgain.payload())
            .containsExactly("sensitive-binary".getBytes(StandardCharsets.UTF_8));
        assertThat(decodedAgain.attributes()).containsExactly("original-attribute");

        assertThat(InteractionObservationEvent.class.getRecordComponents())
            .extracting(component -> component.getName())
            .containsExactly("interactionRef", "evidence")
            .doesNotContain("observingComponentId");
        assertThat(firstClientDiagnostics.get()).doesNotContain("[INTERACTION]");
        assertThat(environment.diagnostics().content())
            .contains(
                "[INTERACTION] [connection=" + firstConnection + "]",
                "[session=" + firstRequest.sessionId() + "]",
                "[flow=CONSUMER_TO_PROVIDER]",
                "[ordinal=1]",
                "[ref=" + firstRequest + "]",
                "schema=test.external:interaction version=1 encodedBytes="
            )
            .doesNotContain(
                "sensitive-binary",
                "original-attribute",
                directInternal,
                directExternal,
                "[INTERACTION] [client-first]",
                "[INTERACTION] [server]"
            );

        environment.close();
    }

    @Test
    void shouldKeepConcurrentStreamIdentityUniqueOrderedAndSnapshotsDetached() throws Exception {
        Client first = new Client("concurrent-first");
        Client second = new Client("concurrent-second");
        Server server = new Server("direct", "external");
        ConnectionId firstConnection = ConnectionId.between(first.api, server.api);
        ConnectionId secondConnection = ConnectionId.between(second.api, server.api);
        Map<ConnectionId, ConnectionObservations> capabilities =
            new ConcurrentHashMap<>();
        Environment environment = routedEnvironment(
            new EnvironmentBuilder()
                .components(first, second, server)
                .connect(first.api, server.api)
                .connect(second.api, server.api),
            context -> {
                capabilities.put(context.connection().id(), context.observations());
                return ConnectionRoute.routed(context.directTarget());
            }
        ).start();
        assertThat(capabilities).containsOnlyKeys(firstConnection, secondConnection);
        ScenarioJournalSnapshot before = environment.journalSnapshot();
        int beforeSize = before.entries().size();
        InteractionSession firstSessionOne =
            capabilities.get(firstConnection).openSession();
        InteractionSession firstSessionTwo =
            capabilities.get(firstConnection).openSession();
        InteractionSession secondSession =
            capabilities.get(secondConnection).openSession();
        ConcurrentStream firstOneRequests = new ConcurrentStream(
            "first-session-one-requests",
            firstConnection,
            firstSessionOne,
            FlowDirection.CONSUMER_TO_PROVIDER
        );
        ConcurrentStream firstOneResponses = new ConcurrentStream(
            "first-session-one-responses",
            firstConnection,
            firstSessionOne,
            FlowDirection.PROVIDER_TO_CONSUMER
        );
        ConcurrentStream firstTwoRequests = new ConcurrentStream(
            "first-session-two-requests",
            firstConnection,
            firstSessionTwo,
            FlowDirection.CONSUMER_TO_PROVIDER
        );
        ConcurrentStream firstTwoResponses = new ConcurrentStream(
            "first-session-two-responses",
            firstConnection,
            firstSessionTwo,
            FlowDirection.PROVIDER_TO_CONSUMER
        );
        ConcurrentStream secondRequests = new ConcurrentStream(
            "second-session-requests",
            secondConnection,
            secondSession,
            FlowDirection.CONSUMER_TO_PROVIDER
        );
        ConcurrentStream secondResponses = new ConcurrentStream(
            "second-session-responses",
            secondConnection,
            secondSession,
            FlowDirection.PROVIDER_TO_CONSUMER
        );
        List<ConcurrentStream> streams = List.of(
            firstOneRequests,
            firstOneResponses,
            firstTwoRequests,
            firstTwoResponses,
            secondRequests,
            secondResponses
        );
        int workersPerStream = 2;
        int observationsPerWorker = 25;
        int expectedPerStream = workersPerStream * observationsPerWorker;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstContribution = new CountDownLatch(1);
        CountDownLatch concurrentSnapshotCaptured = new CountDownLatch(1);
        AtomicBoolean contributing = new AtomicBoolean(true);
        List<CapturedSnapshot> concurrentSnapshots = new CopyOnWriteArrayList<>();
        List<Future<ContributionResult>> contributions = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(
            streams.size() * workersPerStream + 1
        )) {
            Future<?> snapshotter = executor.submit(() -> {
                start.await();
                if (!firstContribution.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("No concurrent contribution started");
                }
                ScenarioJournalSnapshot firstSnapshot = environment.journalSnapshot();
                concurrentSnapshots.add(
                    new CapturedSnapshot(firstSnapshot, firstSnapshot.entries().size())
                );
                concurrentSnapshotCaptured.countDown();
                while (contributing.get() && concurrentSnapshots.size() < 1_000) {
                    ScenarioJournalSnapshot snapshot = environment.journalSnapshot();
                    concurrentSnapshots.add(
                        new CapturedSnapshot(snapshot, snapshot.entries().size())
                    );
                    Thread.onSpinWait();
                }
                return null;
            });
            for (ConcurrentStream stream : streams) {
                for (int worker = 0; worker < workersPerStream; worker++) {
                    int workerId = worker;
                    contributions.add(executor.submit(() -> {
                        start.await();
                        List<InteractionRef> references =
                            new ArrayList<>(observationsPerWorker);
                        for (int observation = 0;
                             observation < observationsPerWorker;
                             observation++) {
                            references.add(stream.session().observe(
                                stream.direction(),
                                MutableInteractionEvidence.codec(),
                                evidence(
                                    stream.name() + ":" + workerId + ":" + observation
                                )
                            ));
                            if (observation == 0) {
                                firstContribution.countDown();
                                if (!concurrentSnapshotCaptured.await(
                                    10,
                                    TimeUnit.SECONDS
                                )) {
                                    throw new AssertionError(
                                        "No snapshot was captured during contribution"
                                    );
                                }
                            }
                        }
                        return new ContributionResult(stream, List.copyOf(references));
                    }));
                }
            }
            start.countDown();
            List<ContributionResult> results = new ArrayList<>();
            try {
                for (Future<ContributionResult> contribution : contributions) {
                    results.add(contribution.get(10, TimeUnit.SECONDS));
                }
            } finally {
                contributing.set(false);
            }
            snapshotter.get(10, TimeUnit.SECONDS);

            List<InteractionRef> returnedReferences = results.stream()
                .flatMap(result -> result.references().stream())
                .toList();
            List<InteractionRef> storedReferences =
                events(environment.journalSnapshot(), InteractionObservationEvent.class)
                    .stream()
                .map(InteractionObservationEvent::interactionRef)
                .toList();

            assertThat(returnedReferences)
                .hasSize(streams.size() * expectedPerStream);
            assertThat(new HashSet<>(returnedReferences))
                .hasSameSizeAs(returnedReferences);
            assertThat(storedReferences)
                .containsExactlyInAnyOrderElementsOf(returnedReferences);
            assertThat(new HashSet<>(storedReferences))
                .hasSameSizeAs(storedReferences);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.references())
                    .allSatisfy(reference -> {
                        assertThat(reference.connectionId())
                            .isEqualTo(result.stream().connectionId());
                        assertThat(reference.direction())
                            .isEqualTo(result.stream().direction());
                    });
                assertThat(result.references().stream()
                    .map(InteractionRef::ordinal)
                    .toList())
                    .isSorted();
            });
            for (ConcurrentStream stream : streams) {
                List<InteractionRef> streamReferences = referencesFor(results, stream);
                assertThat(streamReferences).hasSize(expectedPerStream);
                assertThat(streamReferences)
                    .extracting(InteractionRef::connectionId)
                    .containsOnly(stream.connectionId());
                assertThat(streamReferences)
                    .extracting(InteractionRef::direction)
                    .containsOnly(stream.direction());
                assertThat(streamReferences)
                    .extracting(InteractionRef::sessionId)
                    .containsOnly(streamReferences.getFirst().sessionId());
                assertThat(streamReferences.stream()
                    .map(InteractionRef::ordinal)
                    .sorted()
                    .toList())
                    .containsExactlyElementsOf(
                        LongStream.rangeClosed(1L, expectedPerStream)
                            .boxed()
                            .toList()
                    );
            }

            SessionId firstSessionOneId = sessionIdFor(results, firstOneRequests);
            SessionId firstSessionOneResponseId =
                sessionIdFor(results, firstOneResponses);
            SessionId firstSessionTwoId = sessionIdFor(results, firstTwoRequests);
            SessionId firstSessionTwoResponseId =
                sessionIdFor(results, firstTwoResponses);
            SessionId secondSessionId = sessionIdFor(results, secondRequests);
            SessionId secondSessionResponseId =
                sessionIdFor(results, secondResponses);

            assertThat(firstSessionOneId).isEqualTo(firstSessionOneResponseId);
            assertThat(firstSessionTwoId).isEqualTo(firstSessionTwoResponseId);
            assertThat(secondSessionId).isEqualTo(secondSessionResponseId);
            assertThat(firstSessionOneId.connectionId()).isEqualTo(firstConnection);
            assertThat(firstSessionTwoId.connectionId()).isEqualTo(firstConnection);
            assertThat(secondSessionId.connectionId()).isEqualTo(secondConnection);
            assertThat(firstSessionOneId).isNotEqualTo(firstSessionTwoId);
            assertThat(firstSessionOneId).isNotEqualTo(secondSessionId);
            assertThat(firstSessionTwoId).isNotEqualTo(secondSessionId);
            assertThat(firstSessionOneId.localValue()).isEqualTo(1L);
            assertThat(firstSessionTwoId.localValue()).isEqualTo(2L);
            assertThat(secondSessionId.localValue()).isEqualTo(1L);
        }

        assertThat(before.entries()).hasSize(beforeSize);
        assertThatThrownBy(() -> before.entries().add(before.entries().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(concurrentSnapshots).isNotEmpty();
        assertThat(concurrentSnapshots.getFirst().sizeAtCapture())
            .isGreaterThan(beforeSize)
            .isLessThan(environment.journalSnapshot().entries().size());
        assertThat(concurrentSnapshots)
            .allSatisfy(captured -> {
                assertThat(captured.snapshot().entries()).hasSize(captured.sizeAtCapture());
                assertThatThrownBy(() -> captured.snapshot().entries().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            });

        environment.close();
    }

    private static List<InteractionRef> referencesFor(
        List<ContributionResult> results,
        ConcurrentStream stream
    ) {
        return results.stream()
            .filter(result -> result.stream().equals(stream))
            .flatMap(result -> result.references().stream())
            .toList();
    }

    private static SessionId sessionIdFor(
        List<ContributionResult> results,
        ConcurrentStream stream
    ) {
        List<SessionId> sessionIds = referencesFor(results, stream).stream()
            .map(InteractionRef::sessionId)
            .distinct()
            .toList();
        assertThat(sessionIds).singleElement();
        return sessionIds.getFirst();
    }

    @Test
    void shouldExposeOnlyConnectionBoundObservationInputsToRouteProviders() throws Exception {
        Method observe = InteractionSession.class.getMethod(
            "observe",
            FlowDirection.class,
            EvidenceCodec.class,
            Object.class
        );
        Method correlate = InteractionSession.class.getMethod(
            "correlate",
            InteractionRef.class,
            CorrelationContribution.class
        );
        Method prepare = ConnectionRouteProvider.class.getMethod(
            "prepare",
            ConnectionRouteContext.class
        );

        assertThat(observe.getReturnType()).isEqualTo(InteractionRef.class);
        assertThat(Arrays.asList(observe.getParameterTypes()))
            .containsExactly(FlowDirection.class, EvidenceCodec.class, Object.class)
            .doesNotContain(
                ConnectionId.class,
                SessionId.class,
                InteractionRef.class,
                Component.class,
                ScenarioEvent.class,
                ScenarioJournal.class
            );
        assertThat(correlate.getReturnType()).isEqualTo(void.class);
        assertThat(correlate.getParameterTypes())
            .containsExactly(InteractionRef.class, CorrelationContribution.class);
        assertThat(ConnectionObservations.class.getMethods())
            .extracting(Method::getName)
            .containsExactly("openSession");
        assertThat(ConnectionObservations.class.getMethod("openSession").getParameterCount())
            .isZero();
        assertThat(prepare.getParameterTypes())
            .containsExactly(ConnectionRouteContext.class);
        assertThat(ConnectionRouteContext.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(ConnectionRouteContext.class.getDeclaredConstructors())
            .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers())))
            .isTrue();
        assertThat(List.of(
            ConnectionObservations.class,
            InteractionSession.class,
            ConnectionRouteProvider.class,
            ConnectionRouteContext.class
        )).allSatisfy(type ->
            assertThat(type.getMethods())
                .flatExtracting(method -> {
                    List<Class<?>> exposed = new ArrayList<>();
                    exposed.add(method.getReturnType());
                    exposed.addAll(Arrays.asList(method.getParameterTypes()));
                    return exposed;
                })
                .doesNotContain(ScenarioJournal.class, ScenarioEvent.class)
        );
    }

    private static RoutedEnvironment routedEnvironment(EnvironmentBuilder builder,
        ConnectionRouteProvider<ApiEndpoint> provider) {
        ConnectionRouting routing = ConnectionRouting.routed(API, provider);
        return builder.build((topology, logging) ->
            new RoutedEnvironment(topology, logging, routing)
        );
    }

    private static MutableInteractionEvidence evidence(String value) {
        return new MutableInteractionEvidence(
            value.getBytes(StandardCharsets.UTF_8),
            new ArrayList<>()
        );
    }

    private static <T extends ScenarioEvent> List<T> events(
        ScenarioJournalSnapshot snapshot,
        Class<T> eventType
    ) {
        return snapshot.entries().stream()
            .map(entry -> entry.event())
            .filter(eventType::isInstance)
            .map(eventType::cast)
            .toList();
    }

    private static int indexOf(
        List<ScenarioEvent> events,
        Class<? extends ScenarioEvent> eventType
    ) {
        for (int index = 0; index < events.size(); index++) {
            if (eventType.isInstance(events.get(index))) {
                return index;
            }
        }
        return -1;
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

    private record CapturedSnapshot(
        ScenarioJournalSnapshot snapshot,
        int sizeAtCapture
    ) {}

    private record ConcurrentStream(
        String name,
        ConnectionId connectionId,
        InteractionSession session,
        FlowDirection direction
    ) {}

    private record ContributionResult(
        ConcurrentStream stream,
        List<InteractionRef> references
    ) {}

    private static final class Client extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<ApiEndpoint> api;

        private Client(String qualifier) {
            this(
                qualifier,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
        }

        private Client(
            String qualifier,
            ComponentDriver<EmptyConfig, Void> driver
        ) {
            super(
                ComponentId.component(CLIENT, qualifier),
                new EmptyConfig(),
                Void.class,
                driver
            );
            api = requiresAtStartup(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<ApiEndpoint> api;

        private Server(String internal, String external) {
            super(
                ComponentId.component(SERVER),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .provides(
                        ((Server) component).api,
                        binding(new ApiEndpoint(internal), new ApiEndpoint(external))
                    )
                    .build()
            );
            api = provides(this, "api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class RoutedEnvironment extends Environment {
        private RoutedEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, ConnectionRouting routing) {
            super(topology, logging, routing);
        }
    }
}
