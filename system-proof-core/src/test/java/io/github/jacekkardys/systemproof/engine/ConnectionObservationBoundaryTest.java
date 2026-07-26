package io.github.jacekkardys.systemproof.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

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
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.InteractionRef;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.journal.SessionId;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.Contract;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.model.InteractionSpec;
import io.github.jacekkardys.systemproof.model.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

class ConnectionObservationBoundaryTest {
    private static final ComponentType CLIENT = ComponentType.of("client");
    private static final ComponentType SERVER = ComponentType.of("server");
    private static final Contract<ApiEndpoint> API = contract("api", ApiEndpoint.class);

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
            Environment.environment()
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
        Client client = new Client("concurrent");
        Server server = new Server("direct", "external");
        AtomicReference<ConnectionObservations> capability = new AtomicReference<>();
        Environment environment = routedEnvironment(
            Environment.environment()
                .components(client, server)
                .connect(client.api, server.api),
            context -> {
                capability.set(context.observations());
                return ConnectionRoute.routed(context.directTarget());
            }
        ).start();
        ScenarioJournalSnapshot before = environment.journalSnapshot();
        int beforeSize = before.entries().size();
        InteractionSession session = capability.get().openSession();
        int workers = 8;
        int observationsPerWorker = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean contributing = new AtomicBoolean(true);
        List<CapturedSnapshot> concurrentSnapshots = new CopyOnWriteArrayList<>();
        List<Future<List<InteractionRef>>> contributions = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(workers + 1)) {
            Future<?> snapshotter = executor.submit(() -> {
                start.await();
                while (contributing.get() && concurrentSnapshots.size() < 1_000) {
                    ScenarioJournalSnapshot snapshot = environment.journalSnapshot();
                    concurrentSnapshots.add(
                        new CapturedSnapshot(snapshot, snapshot.entries().size())
                    );
                    Thread.onSpinWait();
                }
                return null;
            });
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                contributions.add(executor.submit(() -> {
                    start.await();
                    FlowDirection direction = workerId % 2 == 0
                        ? FlowDirection.CONSUMER_TO_PROVIDER
                        : FlowDirection.PROVIDER_TO_CONSUMER;
                    List<InteractionRef> references =
                        new ArrayList<>(observationsPerWorker);
                    for (int observation = 0;
                         observation < observationsPerWorker;
                         observation++) {
                        references.add(session.observe(
                            direction,
                            MutableInteractionEvidence.codec(),
                            evidence(workerId + ":" + observation)
                        ));
                    }
                    return List.copyOf(references);
                }));
            }
            start.countDown();
            List<InteractionRef> returned = new ArrayList<>();
            try {
                for (Future<List<InteractionRef>> contribution : contributions) {
                    returned.addAll(contribution.get(10, TimeUnit.SECONDS));
                }
            } finally {
                contributing.set(false);
            }
            snapshotter.get(10, TimeUnit.SECONDS);

            List<InteractionObservationEvent> stored =
                events(environment.journalSnapshot(), InteractionObservationEvent.class)
                    .stream()
                    .filter(event -> event.interactionRef().sessionId()
                        .equals(returned.getFirst().sessionId()))
                    .toList();
            List<InteractionRef> storedReferences = stored.stream()
                .map(InteractionObservationEvent::interactionRef)
                .toList();
            int expectedPerDirection = workers / 2 * observationsPerWorker;

            assertThat(storedReferences).hasSize(workers * observationsPerWorker);
            assertThat(new HashSet<>(storedReferences)).hasSameSizeAs(storedReferences);
            assertThat(storedReferences).containsExactlyInAnyOrderElementsOf(returned);
            for (FlowDirection direction : FlowDirection.values()) {
                assertThat(storedReferences.stream()
                    .filter(reference -> reference.direction() == direction)
                    .map(InteractionRef::ordinal)
                    .toList())
                    .containsExactlyElementsOf(
                        LongStream.rangeClosed(1L, expectedPerDirection)
                            .boxed()
                            .toList()
                    );
            }
        }

        assertThat(before.entries()).hasSize(beforeSize);
        assertThatThrownBy(() -> before.entries().add(before.entries().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(concurrentSnapshots).isNotEmpty();
        assertThat(concurrentSnapshots)
            .allSatisfy(captured -> {
                assertThat(captured.snapshot().entries()).hasSize(captured.sizeAtCapture());
                assertThatThrownBy(() -> captured.snapshot().entries().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            });

        environment.close();
    }

    @Test
    void shouldExposeOnlyConnectionBoundObservationInputsToRouteProviders() throws Exception {
        Method observe = InteractionSession.class.getMethod(
            "observe",
            FlowDirection.class,
            EvidenceCodec.class,
            Object.class
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

    private static RoutedEnvironment routedEnvironment(
        Environment.Builder builder,
        ConnectionRouteProvider<ApiEndpoint> provider
    ) {
        return new RoutedEnvironment(
            builder,
            ConnectionRouting.routed(API, provider)
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
            api = requiresAtStartup("api", API, Invocation.INSTANCE, Http.INSTANCE);
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
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }
    }

    private static final class RoutedEnvironment extends Environment {
        private RoutedEnvironment(Builder builder, ConnectionRouting routing) {
            super(builder, routing);
        }
    }
}
