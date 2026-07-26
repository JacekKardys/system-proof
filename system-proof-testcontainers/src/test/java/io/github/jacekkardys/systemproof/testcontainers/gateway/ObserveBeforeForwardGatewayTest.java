package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.engine.ConnectionObservations;
import io.github.jacekkardys.systemproof.engine.ForwardingDecision;
import io.github.jacekkardys.systemproof.engine.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.engine.InteractionSession;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.journal.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.InteractionRef;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.SessionId;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.model.ObservationRequirement;

class ObserveBeforeForwardGatewayTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final ProtocolLimits LIMITS = new ProtocolLimits(128, 256);
    private static final ConnectionId FIRST_CONNECTION =
        ConnectionId.of("client[].api->server[].api");
    private static final ConnectionId SECOND_CONNECTION =
        ConnectionId.of("client[second].api->server[].api");

    @Test
    void shouldRecordAndDecideBeforeForwardingExactOrderedBytesInBothDirections()
        throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        CountDownLatch decisionEntered = new CountDownLatch(1);
        CountDownLatch allowDecision = new CountDownLatch(1);
        List<InteractionRef> decisions = new ArrayList<>();
        InteractionDecisionCoordinator coordinator = interactionRef -> {
            assertThat(observations.contains(interactionRef)).isTrue();
            synchronized (decisions) {
                decisions.add(interactionRef);
            }
            if (decisions.size() == 1) {
                decisionEntered.countDown();
                await(allowDecision, "first forwarding decision was not released");
            }
            return ForwardingDecision.FORWARD;
        };
        byte[] first = LengthPrefixedProtocolAdapter.frame("alpha");
        byte[] second = LengthPrefixedProtocolAdapter.frame("beta");
        byte[] coalesced = concat(first, second);

        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            coordinator,
            new LengthPrefixedProtocolAdapter(),
            LIMITS
        )) {
            fixture.client().getOutputStream().write(coalesced);
            fixture.client().getOutputStream().flush();

            assertThat(decisionEntered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("coordinator was reached")
                .isTrue();
            assertNoVisibleBytes(fixture.targetPeer());
            allowDecision.countDown();
            assertThat(readExactly(fixture.targetPeer(), coalesced.length))
                .isEqualTo(coalesced);

            byte[] response = LengthPrefixedProtocolAdapter.frame("response");
            fixture.targetPeer().getOutputStream().write(response);
            fixture.targetPeer().getOutputStream().flush();
            assertThat(readExactly(fixture.client(), response.length)).isEqualTo(response);

            List<InteractionObservationEvent> events = observations.events();
            assertThat(events).hasSize(3);
            assertThat(events)
                .extracting(event -> event.interactionRef().direction())
                .containsExactly(
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    FlowDirection.PROVIDER_TO_CONSUMER
                );
            assertThat(events)
                .extracting(event -> event.interactionRef().ordinal())
                .containsExactly(1L, 2L, 1L);
            assertThat(events)
                .extracting(event -> event.interactionRef().sessionId())
                .containsOnly(events.getFirst().interactionRef().sessionId());
            assertThat(events)
                .extracting(event -> event.evidence().decode(
                    LengthPrefixedProtocolAdapter.CODEC
                ).payloadBytes())
                .containsExactly(5, 4, 8);
            assertThat(events)
                .extracting(event -> event.evidence().decode(
                    LengthPrefixedProtocolAdapter.CODEC
                ).payloadSha256())
                .containsExactly(
                    LengthPrefixedProtocolAdapter.sha256("alpha".getBytes(UTF_8)),
                    LengthPrefixedProtocolAdapter.sha256("beta".getBytes(UTF_8)),
                    LengthPrefixedProtocolAdapter.sha256("response".getBytes(UTF_8))
                );
            assertThat(decisions).hasSize(3);
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.ACTIVE);

            SessionId firstSession = events.getFirst().interactionRef().sessionId();
            fixture.reconnect();
            byte[] reconnect = LengthPrefixedProtocolAdapter.frame("reconnect");
            fixture.client().getOutputStream().write(reconnect);
            fixture.client().getOutputStream().flush();
            assertThat(readExactly(fixture.targetPeer(), reconnect.length))
                .isEqualTo(reconnect);
            assertThat(observations.events().getLast().interactionRef().sessionId())
                .isNotEqualTo(firstSession);
        }
    }

    @Test
    void shouldPropagateHalfCloseOnlyAtACompleteFrameBoundary() throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter(),
            LIMITS
        )) {
            byte[] request = LengthPrefixedProtocolAdapter.frame("request");
            fixture.client().getOutputStream().write(request);
            fixture.client().shutdownOutput();

            assertThat(readExactly(fixture.targetPeer(), request.length)).isEqualTo(request);
            assertThat(fixture.targetPeer().getInputStream().read()).isEqualTo(-1);

            byte[] response = LengthPrefixedProtocolAdapter.frame("response");
            fixture.targetPeer().getOutputStream().write(response);
            fixture.targetPeer().shutdownOutput();
            assertThat(readExactly(fixture.client(), response.length)).isEqualTo(response);
            assertThat(fixture.client().getInputStream().read()).isEqualTo(-1);
            assertThat(observations.events()).hasSize(2);
        }
    }

    @Test
    void shouldFailRequiredSessionsClosedForEveryProtocolFailureAndLimit()
        throws Exception {
        Map<Integer, ProtocolFailureKind> controls = Map.of(
            LengthPrefixedProtocolAdapter.MALFORMED,
            ProtocolFailureKind.MALFORMED_INPUT,
            LengthPrefixedProtocolAdapter.UNSUPPORTED_ENCRYPTION,
            ProtocolFailureKind.UNSUPPORTED_ENCRYPTION,
            LengthPrefixedProtocolAdapter.UNSUPPORTED_NEGOTIATION,
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
            LengthPrefixedProtocolAdapter.DESYNCHRONIZED,
            ProtocolFailureKind.DESYNCHRONIZATION,
            LengthPrefixedProtocolAdapter.AMBIGUOUS,
            ProtocolFailureKind.AMBIGUOUS_FRAMING
        );
        for (int control : controls.keySet()) {
            assertRequiredFailure(
                new LengthPrefixedProtocolAdapter(),
                LIMITS,
                LengthPrefixedProtocolAdapter.control(control),
                false
            );
        }

        byte[] excessive = java.nio.ByteBuffer.allocate(Integer.BYTES)
            .putInt(LIMITS.maximumFrameBytes())
            .array();
        assertRequiredFailure(
            new LengthPrefixedProtocolAdapter(),
            LIMITS,
            excessive,
            false
        );

        AtomicInteger maximumBuffered = new AtomicInteger();
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> neverCompletes =
            delegatingAdapter(
                LengthPrefixedProtocolAdapter.CODEC,
                limits -> direction ->
                    new ProtocolStream<>() {
                        @Override
                        public ProtocolDecodeResult<
                            LengthPrefixedProtocolAdapter.FrameEvidence
                        > decode(java.nio.ByteBuffer buffered) {
                            maximumBuffered.accumulateAndGet(
                                buffered.remaining(),
                                Math::max
                            );
                            return ProtocolDecodeResult.needMoreData();
                        }
                    }
            );
        ProtocolLimits smallBuffer = new ProtocolLimits(8, 8);
        assertRequiredFailure(
            neverCompletes,
            smallBuffer,
            new byte[smallBuffer.maximumBufferedBytes()],
            false
        );
        assertThat(maximumBuffered).hasValue(smallBuffer.maximumBufferedBytes());

        byte[] incomplete = Arrays.copyOf(
            LengthPrefixedProtocolAdapter.frame("incomplete"),
            Integer.BYTES + 1
        );
        assertRequiredFailure(
            new LengthPrefixedProtocolAdapter(),
            LIMITS,
            incomplete,
            true
        );
    }

    @Test
    void shouldFailClosedOnCodecJournalAndCoordinatorFailure() throws Exception {
        EvidenceCodec<LengthPrefixedProtocolAdapter.FrameEvidence> failingCodec =
            new EvidenceCodec<>() {
                @Override
                public io.github.jacekkardys.systemproof.journal.EvidenceSchemaId schemaId() {
                    return LengthPrefixedProtocolAdapter.CODEC.schemaId();
                }

                @Override
                public byte[] encode(
                    LengthPrefixedProtocolAdapter.FrameEvidence evidence
                ) {
                    throw new IllegalStateException("codec failure with secret payload");
                }

                @Override
                public LengthPrefixedProtocolAdapter.FrameEvidence decode(byte[] encodedEvidence) {
                    return LengthPrefixedProtocolAdapter.CODEC.decode(encodedEvidence);
                }
            };
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> codecFailure =
            delegatingAdapter(
                failingCodec,
                new LengthPrefixedProtocolAdapter()::openSession
            );
        RecordingObservations codecObservations =
            new RecordingObservations(FIRST_CONNECTION);
        assertPipelineFailure(
            codecObservations,
            interactionRef -> ForwardingDecision.FORWARD,
            codecFailure
        );
        assertThat(codecObservations.events()).isEmpty();

        RecordingObservations journalFailure =
            new RecordingObservations(FIRST_CONNECTION, true);
        assertPipelineFailure(
            journalFailure,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter()
        );
        assertThat(journalFailure.events()).isEmpty();

        RecordingObservations coordinatorObservations =
            new RecordingObservations(FIRST_CONNECTION);
        assertPipelineFailure(
            coordinatorObservations,
            interactionRef -> {
                throw new IllegalStateException("coordinator failure with secret payload");
            },
            new LengthPrefixedProtocolAdapter()
        );
        assertThat(coordinatorObservations.events()).hasSize(1);
    }

    @Test
    void shouldKeepDisabledAndUnsupportedOptionalRoutesExplicitlyTransparent()
        throws Exception {
        byte[] arbitraryTcpBytes = "not-a-frame".getBytes(UTF_8);
        for (ObservationRequirement requirement : List.of(
            ObservationRequirement.DISABLED,
            ObservationRequirement.OPTIONAL
        )) {
            RecordingObservations observations =
                new RecordingObservations(FIRST_CONNECTION);
            try (RouteFixture fixture = RouteFixture.open(
                FIRST_CONNECTION,
                requirement,
                observations,
                interactionRef -> {
                    throw new AssertionError("Transparent traffic must not be decided");
                },
                null,
                null
            )) {
                fixture.client().getOutputStream().write(arbitraryTcpBytes);
                fixture.client().getOutputStream().flush();
                assertThat(readExactly(fixture.targetPeer(), arbitraryTcpBytes.length))
                    .isEqualTo(arbitraryTcpBytes);
                assertThat(observations.events()).isEmpty();
                assertThat(fixture.route().observationStatus()).isEqualTo(
                    requirement == ObservationRequirement.DISABLED
                        ? EffectiveObservationStatus.DISABLED
                        : EffectiveObservationStatus.UNSUPPORTED
                );
            }
        }
    }

    @Test
    void shouldDegradeOptionalObservationWithoutTransparentRetry() throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        try (RouteFixture fixture = RouteFixture.open(
            FIRST_CONNECTION,
            ObservationRequirement.OPTIONAL,
            observations,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter(),
            LIMITS
        )) {
            fixture.client().getOutputStream().write(
                LengthPrefixedProtocolAdapter.control(
                    LengthPrefixedProtocolAdapter.UNSUPPORTED_ENCRYPTION
                )
            );
            fixture.client().getOutputStream().flush();
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.DEGRADED);

            fixture.reconnect();
            byte[] valid = LengthPrefixedProtocolAdapter.frame("must-not-bypass");
            fixture.client().getOutputStream().write(valid);
            fixture.client().getOutputStream().flush();
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(observations.events()).isEmpty();
        }
    }

    @Test
    void shouldShareOneCoordinatorAcrossConnectionsWhileKeepingIdentityIsolated()
        throws Exception {
        List<InteractionRef> decisions = java.util.Collections.synchronizedList(
            new ArrayList<>()
        );
        InteractionDecisionCoordinator coordinator = interactionRef -> {
            decisions.add(interactionRef);
            return ForwardingDecision.FORWARD;
        };
        RecordingObservations firstObservations =
            new RecordingObservations(FIRST_CONNECTION);
        RecordingObservations secondObservations =
            new RecordingObservations(SECOND_CONNECTION);

        try (
            RouteFixture first = RouteFixture.required(
                FIRST_CONNECTION,
                firstObservations,
                coordinator,
                new LengthPrefixedProtocolAdapter(),
                LIMITS
            );
            RouteFixture second = RouteFixture.required(
                SECOND_CONNECTION,
                secondObservations,
                coordinator,
                new LengthPrefixedProtocolAdapter(),
                LIMITS
            )
        ) {
            byte[] firstFrame = LengthPrefixedProtocolAdapter.frame("first");
            byte[] secondFrame = LengthPrefixedProtocolAdapter.frame("second");
            first.client().getOutputStream().write(firstFrame);
            first.client().getOutputStream().flush();
            second.client().getOutputStream().write(secondFrame);
            second.client().getOutputStream().flush();
            assertThat(readExactly(first.targetPeer(), firstFrame.length))
                .isEqualTo(firstFrame);
            assertThat(readExactly(second.targetPeer(), secondFrame.length))
                .isEqualTo(secondFrame);
            assertThat(decisions)
                .extracting(InteractionRef::connectionId)
                .containsExactlyInAnyOrder(FIRST_CONNECTION, SECOND_CONNECTION);
            assertThat(decisions)
                .extracting(InteractionRef::ordinal)
                .containsOnly(1L);
        }
    }

    private static void assertRequiredFailure(
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
        ProtocolLimits limits,
        byte[] bytes,
        boolean halfClose
    ) throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            interactionRef -> ForwardingDecision.FORWARD,
            adapter,
            limits
        )) {
            fixture.client().getOutputStream().write(bytes);
            fixture.client().getOutputStream().flush();
            if (halfClose) {
                fixture.client().shutdownOutput();
            }
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(observations.events()).isEmpty();
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
        }
    }

    private static void assertPipelineFailure(
        RecordingObservations observations,
        InteractionDecisionCoordinator coordinator,
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter
    ) throws Exception {
        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            coordinator,
            adapter,
            LIMITS
        )) {
            fixture.client().getOutputStream().write(
                LengthPrefixedProtocolAdapter.frame("undecided-secret")
            );
            fixture.client().getOutputStream().flush();
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
        }
    }

    private static ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence>
        delegatingAdapter(
            EvidenceCodec<LengthPrefixedProtocolAdapter.FrameEvidence> codec,
            SessionFactory sessions
        ) {
        return new ProtocolAdapter<>() {
            @Override
            public EvidenceCodec<LengthPrefixedProtocolAdapter.FrameEvidence> evidenceCodec() {
                return codec;
            }

            @Override
            public ProtocolSession<LengthPrefixedProtocolAdapter.FrameEvidence> openSession(
                ProtocolLimits limits
            ) {
                return sessions.open(limits);
            }
        };
    }

    private static void assertNoVisibleBytes(Socket socket) throws IOException {
        int originalTimeout = socket.getSoTimeout();
        socket.setSoTimeout(150);
        try {
            assertThatThrownBy(() -> socket.getInputStream().read())
                .isInstanceOf(SocketTimeoutException.class);
        } finally {
            socket.setSoTimeout(originalTimeout);
        }
    }

    private static void assertNoForwardedBytesAfterClose(Socket socket) throws IOException {
        socket.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        try {
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError("Failed session did not close within the timeout", timeout);
        }
    }

    private static byte[] readExactly(Socket socket, int expectedBytes) throws IOException {
        socket.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        byte[] value = socket.getInputStream().readNBytes(expectedBytes);
        assertThat(value).hasSize(expectedBytes);
        return value;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static void await(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(failureMessage);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, interrupted);
        }
    }

    @FunctionalInterface
    private interface SessionFactory {
        ProtocolSession<LengthPrefixedProtocolAdapter.FrameEvidence> open(
            ProtocolLimits limits
        );
    }

    private static final class RecordingObservations implements ConnectionObservations {
        private final ConnectionId connectionId;
        private final ScenarioJournal journal = new ScenarioJournal();
        private final AtomicLong nextSession = new AtomicLong(SessionId.FIRST_VALUE);
        private final boolean failJournal;

        private RecordingObservations(ConnectionId connectionId) {
            this(connectionId, false);
        }

        private RecordingObservations(ConnectionId connectionId, boolean failJournal) {
            this.connectionId = connectionId;
            this.failJournal = failJournal;
        }

        @Override
        public InteractionSession openSession() {
            return new RecordingSession(new SessionId(
                connectionId,
                nextSession.getAndIncrement()
            ));
        }

        private boolean contains(InteractionRef interactionRef) {
            return events().stream()
                .anyMatch(event -> event.interactionRef().equals(interactionRef));
        }

        private List<InteractionObservationEvent> events() {
            return journal.snapshot().entries().stream()
                .map(entry -> entry.event())
                .filter(InteractionObservationEvent.class::isInstance)
                .map(InteractionObservationEvent.class::cast)
                .toList();
        }

        private final class RecordingSession implements InteractionSession {
            private final SessionId sessionId;
            private final EnumMap<FlowDirection, AtomicLong> ordinals =
                new EnumMap<>(FlowDirection.class);

            private RecordingSession(SessionId sessionId) {
                this.sessionId = sessionId;
                for (FlowDirection direction : FlowDirection.values()) {
                    ordinals.put(direction, new AtomicLong(InteractionRef.FIRST_ORDINAL));
                }
            }

            @Override
            public <T> InteractionRef observe(
                FlowDirection direction,
                EvidenceCodec<T> codec,
                T evidence
            ) {
                if (failJournal) {
                    throw new IllegalStateException("journal failure with secret payload");
                }
                InteractionRef interactionRef = new InteractionRef(
                    sessionId,
                    direction,
                    ordinals.get(direction).getAndIncrement()
                );
                journal.append(new InteractionObservationEvent(
                    interactionRef,
                    EvidenceSnapshot.capture(codec, evidence)
                ));
                return interactionRef;
            }
        }
    }

    private static final class RouteFixture implements AutoCloseable {
        private final ServerSocket targetListener;
        private final GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route;
        private Socket client;
        private Socket targetPeer;

        private RouteFixture(
            ServerSocket targetListener,
            GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route
        ) throws IOException {
            this.targetListener = targetListener;
            this.route = route;
            route.start();
            connectPair();
        }

        private static RouteFixture required(
            ConnectionId connectionId,
            ConnectionObservations observations,
            InteractionDecisionCoordinator coordinator,
            ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
            ProtocolLimits limits
        ) throws IOException {
            return open(
                connectionId,
                ObservationRequirement.REQUIRED,
                observations,
                coordinator,
                adapter,
                limits
            );
        }

        private static RouteFixture open(
            ConnectionId connectionId,
            ObservationRequirement requirement,
            ConnectionObservations observations,
            InteractionDecisionCoordinator coordinator,
            ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
            ProtocolLimits limits
        ) throws IOException {
            ServerSocket targetListener = new ServerSocket();
            targetListener.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                0
            ));
            targetListener.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route =
                GatewayRoute.open(
                    connectionId,
                    new InetSocketAddress("127.0.0.1", targetListener.getLocalPort()),
                    TIMEOUT,
                    TIMEOUT,
                    requirement,
                    observations,
                    coordinator,
                    adapter,
                    limits
                );
            try {
                return new RouteFixture(targetListener, route);
            } catch (IOException | RuntimeException failure) {
                try {
                    route.close();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                try {
                    targetListener.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route() {
            return route;
        }

        private Socket client() {
            return client;
        }

        private Socket targetPeer() {
            return targetPeer;
        }

        private void reconnect() throws IOException {
            client.close();
            targetPeer.close();
            connectPair();
        }

        private void connectPair() throws IOException {
            client = new Socket();
            client.connect(
                new InetSocketAddress("127.0.0.1", route.listenerPort()),
                Math.toIntExact(TIMEOUT.toMillis())
            );
            client.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            targetPeer = targetListener.accept();
            targetPeer.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            failure = close(client, failure);
            failure = close(targetPeer, failure);
            failure = close(route, failure);
            failure = close(targetListener, failure);
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        private static Throwable close(AutoCloseable resource, Throwable first) {
            try {
                resource.close();
                return first;
            } catch (Exception | Error failure) {
                if (first == null) {
                    return failure;
                }
                first.addSuppressed(failure);
                return first;
            }
        }
    }
}
