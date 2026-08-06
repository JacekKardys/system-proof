package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static io.github.jacekkardys.systemproof.observation.ForwardingDecision.FORWARD;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.environment.ConnectionObservations;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.environment.InteractionSession;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.ForwardingPermit;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RecordedInteraction;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence;
import io.github.jacekkardys.systemproof.smpp.SmppPdus;
import io.github.jacekkardys.systemproof.smpp.SmppProtocolAdapter;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

class SmppRequiredObservationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final ProtocolLimits LIMITS = new ProtocolLimits(4096, 8192);
    private static final ConnectionId CONNECTION = ConnectionId.of(
        "client[].smpp->server[].smpp"
    );
    private static final CorrelationKey KEY = CorrelationKey.ofDigest(
        new CorrelationKeySchema("system-proof.smpp.test", "zero-sequence", 1),
        new byte[32]
    );

    @Test
    void shouldNotRecordCorrelateOrForwardAZeroSequenceDeliver() throws Exception {
        SmppProtocolAdapter adapter = correlatedAdapter();
        RecordingObservations observations = new RecordingObservations();
        try (RouteFixture fixture = RouteFixture.open(adapter, observations)) {
            bind(fixture);
            int evidenceBefore = observations.evidence().size();

            write(fixture.targetPeer(), SmppPdus.deliver(0, "zero-request"));

            assertNoForwardedBytesAfterClose(fixture.client());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
            assertThat(observations.evidence()).hasSize(evidenceBefore)
                .noneMatch(DeliverSmCompleted.class::isInstance);
            assertThat(observations.correlations()).isEmpty();
        }
    }

    @Test
    void shouldNotRecordCorrelateOrForwardAZeroSequenceResponse() throws Exception {
        SmppProtocolAdapter adapter = correlatedAdapter();
        RecordingObservations observations = new RecordingObservations();
        try (RouteFixture fixture = RouteFixture.open(adapter, observations)) {
            bind(fixture);
            byte[] validDeliver = SmppPdus.deliver(0x8000_0000L, "high-bit");
            write(fixture.targetPeer(), validDeliver);
            assertThat(readExactly(fixture.client(), validDeliver.length))
                .isEqualTo(validDeliver);
            int evidenceBefore = observations.evidence().size();
            int correlationsBefore = observations.correlations().size();

            write(fixture.client(), SmppPdus.deliverResponse(0, 0));

            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
            assertThat(observations.evidence()).hasSize(evidenceBefore)
                .noneMatch(DeliverSmResponseCompleted.class::isInstance);
            assertThat(observations.correlations()).hasSize(correlationsBefore);
            assertThat(correlationsBefore).isEqualTo(1);
        }
    }

    @Test
    void shouldFailClosedWhenDeliverFollowsPropagatedConsumerEof() throws Exception {
        SmppProtocolAdapter adapter = correlatedAdapter();
        RecordingObservations observations = new RecordingObservations();
        try (RouteFixture fixture = RouteFixture.open(adapter, observations)) {
            bind(fixture);
            int evidenceBefore = observations.evidence().size();
            int correlationsBefore = observations.correlations().size();

            fixture.client().shutdownOutput();
            assertThat(fixture.targetPeer().getInputStream().read()).isEqualTo(-1);
            write(fixture.targetPeer(), SmppPdus.deliver(2, "after-consumer-eof"));

            assertNoForwardedBytesAfterClose(fixture.client());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
            assertThat(observations.evidence()).hasSize(evidenceBefore)
                .noneMatch(DeliverSmCompleted.class::isInstance);
            assertThat(observations.correlations()).hasSize(correlationsBefore);
            assertThat(correlationsBefore).isZero();
        }
    }

    private static SmppProtocolAdapter correlatedAdapter() {
        return new SmppProtocolAdapter(interaction -> Optional.of(KEY));
    }

    private static void bind(RouteFixture fixture) throws IOException {
        byte[] request = SmppPdus.bindRequest(1);
        write(fixture.client(), request);
        assertThat(readExactly(fixture.targetPeer(), request.length)).isEqualTo(request);

        byte[] response = SmppPdus.bindResponse(1, 0);
        write(fixture.targetPeer(), response);
        assertThat(readExactly(fixture.client(), response.length)).isEqualTo(response);
    }

    private static void write(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static byte[] readExactly(Socket socket, int expectedBytes)
        throws IOException {
        socket.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        byte[] value = socket.getInputStream().readNBytes(expectedBytes);
        assertThat(value).hasSize(expectedBytes);
        return value;
    }

    private static void assertNoForwardedBytesAfterClose(Socket socket)
        throws IOException {
        socket.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        try {
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError("Failed SMPP session did not close", timeout);
        }
    }

    private static ForwardingPermit immediateForwardPermit() {
        return new ForwardingPermit() {
            @Override
            public io.github.jacekkardys.systemproof.observation.ForwardingDecision
                awaitDecision() {
                return FORWARD;
            }

            @Override
            public void forwarded() {}

            @Override
            public void writeFailed() {}

            @Override
            public void abandoned() {}
        };
    }

    private static final class RecordingObservations implements ConnectionObservations {
        private final List<Object> evidence = Collections.synchronizedList(
            new ArrayList<>()
        );
        private final List<CorrelationContribution<?>> correlations =
            Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong nextSession = new AtomicLong(SessionId.FIRST_VALUE);

        @Override
        public InteractionSession openSession() {
            SessionId sessionId = new SessionId(CONNECTION, nextSession.getAndIncrement());
            return new InteractionSession() {
                private final EnumMap<FlowDirection, AtomicLong> ordinals = ordinals();

                @Override
                public <T> RecordedInteraction record(
                    FlowDirection direction,
                    EvidenceCodec<T> codec,
                    T value
                ) {
                    EvidenceSnapshot snapshot = EvidenceSnapshot.capture(codec, value);
                    evidence.add(value);
                    return new RecordedInteraction(
                        new InteractionRef(
                            sessionId,
                            direction,
                            ordinals.get(direction).getAndIncrement()
                        ),
                        snapshot
                    );
                }

                @Override
                public void correlate(
                    InteractionRef interactionRef,
                    CorrelationContribution<?> contribution
                ) {
                    correlations.add(contribution);
                }
            };
        }

        private List<SmppEvidence> evidence() {
            synchronized (evidence) {
                return evidence.stream()
                    .map(SmppEvidence.class::cast)
                    .toList();
            }
        }

        private List<CorrelationContribution<?>> correlations() {
            synchronized (correlations) {
                return List.copyOf(correlations);
            }
        }

        private static EnumMap<FlowDirection, AtomicLong> ordinals() {
            EnumMap<FlowDirection, AtomicLong> result = new EnumMap<>(
                FlowDirection.class
            );
            for (FlowDirection direction : FlowDirection.values()) {
                result.put(direction, new AtomicLong(InteractionRef.FIRST_ORDINAL));
            }
            return result;
        }
    }

    private static final class RouteFixture implements AutoCloseable {
        private final ServerSocket targetListener;
        private final GatewayRoute<SmppEvidence> route;
        private final Socket client;
        private final Socket targetPeer;

        private RouteFixture(
            ServerSocket targetListener,
            GatewayRoute<SmppEvidence> route,
            Socket client,
            Socket targetPeer
        ) {
            this.targetListener = targetListener;
            this.route = route;
            this.client = client;
            this.targetPeer = targetPeer;
        }

        private static RouteFixture open(
            SmppProtocolAdapter adapter,
            RecordingObservations observations
        ) throws IOException {
            ServerSocket targetListener = new ServerSocket();
            GatewayRoute<SmppEvidence> route = null;
            Socket client = null;
            Socket targetPeer = null;
            try {
                targetListener.bind(new InetSocketAddress(
                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                    0
                ));
                targetListener.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
                route = GatewayRoute.open(
                    CONNECTION,
                    new InetSocketAddress(
                        "127.0.0.1",
                        targetListener.getLocalPort()
                    ),
                    TIMEOUT,
                    TIMEOUT,
                    ObservationRequirement.REQUIRED,
                    observations,
                    interaction -> immediateForwardPermit(),
                    adapter,
                    LIMITS
                );
                route.start();
                client = new Socket();
                client.connect(
                    new InetSocketAddress("127.0.0.1", route.listenerPort()),
                    Math.toIntExact(TIMEOUT.toMillis())
                );
                client.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
                targetPeer = targetListener.accept();
                targetPeer.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
                return new RouteFixture(targetListener, route, client, targetPeer);
            } catch (IOException | RuntimeException failure) {
                closeAfterFailure(targetPeer, failure);
                closeAfterFailure(client, failure);
                closeAfterFailure(route, failure);
                closeAfterFailure(targetListener, failure);
                throw failure;
            }
        }

        private GatewayRoute<SmppEvidence> route() {
            return route;
        }

        private Socket client() {
            return client;
        }

        private Socket targetPeer() {
            return targetPeer;
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
            if (resource == null) {
                return first;
            }
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

        private static void closeAfterFailure(
            AutoCloseable resource,
            Throwable failure
        ) {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
