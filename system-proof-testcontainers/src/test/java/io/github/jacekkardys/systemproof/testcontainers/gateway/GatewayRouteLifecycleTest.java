package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static io.github.jacekkardys.systemproof.observation.ForwardingDecision.FORWARD;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.environment.ConnectionObservations;
import io.github.jacekkardys.systemproof.environment.InteractionSession;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

class GatewayRouteLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final ProtocolLimits LIMITS = new ProtocolLimits(128, 256);
    private static final ConnectionId CONNECTION =
        ConnectionId.of("client[].api->server[].api");

    @Test
    void shouldPreserveRequiredListenerFailureAsPrimaryDuringCleanup() throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32040);
        IOException listenerFailure = new IOException(
            "listener-secret at 127.0.0.1:32040"
        );
        IOException cleanupFailure = new IOException(
            "cleanup-secret at 127.0.0.1:42040"
        );
        listener.failOnClose(cleanupFailure);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        assertThat(route.observationStatus()).isEqualTo(EffectiveObservationStatus.ACTIVE);
        listener.awaitAcceptCalls(1);
        listener.fail(listenerFailure);
        awaitStatus(route, EffectiveObservationStatus.FAILED);

        assertThat(statusReads(route, 1_000))
            .containsOnly(EffectiveObservationStatus.FAILED);
        Throwable thrown = catchThrowable(route::close);

        assertThat(thrown).isSameAs(listenerFailure);
        assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(route.observationStatus()).isEqualTo(EffectiveObservationStatus.FAILED);
        route.close();
        assertThat(listener.closeCalls()).isEqualTo(1);
    }

    @Test
    void shouldRetainSessionLocalSocketFailureUntilRouteCleanup() throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32047);
        IOException listenerFailure = new IOException("accept loop terminated");
        IOException socketFailure = new IOException("session socket cleanup failed");
        ControlledCloseSocket socket = ControlledCloseSocket.failingWith(socketFailure);
        listener.accept(socket);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        socket.awaitSetupEntered();
        listener.awaitAcceptCalls(2);
        listener.fail(listenerFailure);
        awaitStatus(route, EffectiveObservationStatus.FAILED);
        socket.releaseSetup();
        socket.awaitCloseEntered();

        Throwable thrown = catchThrowable(route::close);

        assertThat(thrown).isSameAs(listenerFailure);
        assertThat(thrown.getSuppressed()).containsExactly(socketFailure);
        assertThat(socket.closeCalls()).isEqualTo(1);
    }

    @Test
    void shouldOrderSessionLocalSocketFailuresByRegistrationSequence() throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32048);
        IOException listenerFailure = new IOException("accept loop terminated");
        IOException firstSocketFailure = new IOException("first socket cleanup failed");
        IOException secondSocketFailure = new IOException("second socket cleanup failed");
        ControlledCloseSocket firstSocket =
            ControlledCloseSocket.failingWith(firstSocketFailure);
        ControlledCloseSocket secondSocket =
            ControlledCloseSocket.failingWith(secondSocketFailure);
        listener.accept(firstSocket);
        listener.accept(secondSocket);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        firstSocket.awaitSetupEntered();
        secondSocket.awaitSetupEntered();
        listener.awaitAcceptCalls(3);
        listener.fail(listenerFailure);
        awaitStatus(route, EffectiveObservationStatus.FAILED);
        secondSocket.releaseSetup();
        secondSocket.awaitCloseEntered();
        firstSocket.releaseSetup();
        firstSocket.awaitCloseEntered();

        Throwable thrown = catchThrowable(route::close);

        assertThat(thrown).isSameAs(listenerFailure);
        assertThat(thrown.getSuppressed())
            .containsExactly(firstSocketFailure, secondSocketFailure);
        assertThat(firstSocket.closeCalls()).isEqualTo(1);
        assertThat(secondSocket.closeCalls()).isEqualTo(1);
    }

    @Test
    void shouldFinishConcurrentSessionSocketCleanupExactlyOnceBeforeRouteCloseReturns()
        throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32049);
        IOException listenerFailure = new IOException("accept loop terminated");
        IOException socketFailure = new IOException("racing socket cleanup failed");
        ControlledCloseSocket socket = ControlledCloseSocket.blockedClose(socketFailure);
        listener.accept(socket);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        socket.awaitSetupEntered();
        listener.awaitAcceptCalls(2);
        listener.fail(listenerFailure);
        awaitStatus(route, EffectiveObservationStatus.FAILED);
        socket.releaseSetup();
        socket.awaitCloseEntered();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> close = executor.submit(() -> catchThrowable(route::close));
            listener.awaitCloseEntered();
            assertThat(close.isDone()).isFalse();
            socket.releaseClose();

            Throwable thrown = close.get(5, TimeUnit.SECONDS);
            assertThat(thrown).isSameAs(listenerFailure);
            assertThat(thrown.getSuppressed()).containsExactly(socketFailure);
            Throwable[] suppressedAfterFirstClose = thrown.getSuppressed();

            route.close();

            assertThat(socket.closeCalls()).isEqualTo(1);
            assertThat(thrown.getSuppressed()).containsExactly(suppressedAfterFirstClose);
        }
    }

    @Test
    void shouldKeepHealthySessionLocalSocketCleanupBestEffort() throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32050);
        IOException socketFailure = new IOException("healthy session socket cleanup failed");
        ControlledCloseSocket socket = ControlledCloseSocket.failingWith(socketFailure);
        listener.accept(socket);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        socket.awaitSetupEntered();
        socket.releaseSetup();
        socket.awaitCloseEntered();

        assertThat(catchThrowable(route::close)).isNull();
        assertThat(socket.closeCalls()).isEqualTo(1);
        assertThat(route.observationStatus()).isEqualTo(EffectiveObservationStatus.INACTIVE);
    }

    @Test
    void shouldDegradeOptionalObservationAndKeepItDegradedAfterClose() throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32041);
        IOException listenerFailure = new IOException("optional listener failed");
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.OPTIONAL,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        listener.awaitAcceptCalls(1);
        listener.fail(listenerFailure);
        awaitStatus(route, EffectiveObservationStatus.DEGRADED);

        assertThat(catchThrowable(route::close)).isSameAs(listenerFailure);
        assertThat(route.observationStatus()).isEqualTo(EffectiveObservationStatus.DEGRADED);
        route.close();
        assertThat(listener.closeCalls()).isEqualTo(1);
    }

    @Test
    void shouldTreatListenerCloseAsExpectedIdempotentShutdown() throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(32042);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        listener.awaitAcceptCalls(1);
        route.close();
        route.close();

        assertThat(route.observationStatus()).isEqualTo(EffectiveObservationStatus.INACTIVE);
        assertThat(listener.closeCalls()).isEqualTo(1);
    }

    @Test
    void shouldResolveListenerFailureAndShutdownRacesByFirstTerminalTransition()
        throws Exception {
        ControllableGatewayListener failedListener =
            ControllableGatewayListener.scripted(32043);
        IOException firstFailure = new IOException("failure won");
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> failedRoute = route(
            ObservationRequirement.REQUIRED,
            failedListener,
            new InetSocketAddress("127.0.0.1", 1)
        );
        failedRoute.start();
        failedListener.awaitAcceptCalls(1);
        failedListener.fail(firstFailure);
        awaitStatus(failedRoute, EffectiveObservationStatus.FAILED);
        assertThat(catchThrowable(failedRoute::close)).isSameAs(firstFailure);

        ControllableGatewayListener closingListener =
            ControllableGatewayListener.scriptedWithBlockedClose(32044);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> closingRoute = route(
            ObservationRequirement.REQUIRED,
            closingListener,
            new InetSocketAddress("127.0.0.1", 1)
        );
        closingRoute.start();
        closingListener.awaitAcceptCalls(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> close = executor.submit(() -> {
                closingRoute.close();
                return null;
            });
            closingListener.awaitCloseEntered();
            ControllableGatewayListener.FailureSignal expectedCloseFailure =
                closingListener.fail(new SocketException("expected close interruption"));
            expectedCloseFailure.awaitDelivery();
            closingListener.releaseClose();
            close.get(5, TimeUnit.SECONDS);
        }

        assertThat(closingRoute.observationStatus())
            .isEqualTo(EffectiveObservationStatus.INACTIVE);
        closingRoute.close();
        assertThat(closingListener.closeCalls()).isEqualTo(1);
    }

    @Test
    void shouldRecordRuntimeAndErrorAcceptLoopTermination() throws Exception {
        assertUncheckedFailure(
            new IllegalStateException("runtime listener secret"),
            32045
        );
        assertUncheckedFailure(
            new AssertionError("error listener secret"),
            32046
        );
    }

    private static void assertUncheckedFailure(Throwable listenerFailure, int port)
        throws Exception {
        ControllableGatewayListener listener = ControllableGatewayListener.scripted(port);
        GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
            ObservationRequirement.REQUIRED,
            listener,
            new InetSocketAddress("127.0.0.1", 1)
        );

        route.start();
        listener.awaitAcceptCalls(1);
        listener.fail(listenerFailure);
        awaitStatus(route, EffectiveObservationStatus.FAILED);

        assertThat(catchThrowable(route::close)).isSameAs(listenerFailure);
        assertThat(route.observationStatus()).isEqualTo(EffectiveObservationStatus.FAILED);
    }

    @Test
    void shouldKeepEstablishedSessionAliveUntilRouteCleanupAfterListenerFailure()
        throws Exception {
        ControllableGatewayListener listener =
            ControllableGatewayListener.delegatingFirstAccept();
        IOException listenerFailure = new IOException("accept loop terminated");
        try (ServerSocket targetListener = new ServerSocket()) {
            targetListener.bind(new InetSocketAddress("127.0.0.1", 0));
            targetListener.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route = route(
                ObservationRequirement.REQUIRED,
                listener,
                new InetSocketAddress("127.0.0.1", targetListener.getLocalPort())
            );
            route.start();
            try (
                Socket client = connect(listener.port());
                Socket targetPeer = targetListener.accept()
            ) {
                exchangeFrame(client, targetPeer, "before-failure");
                listener.awaitAcceptCalls(2);
                listener.fail(listenerFailure);
                awaitStatus(route, EffectiveObservationStatus.FAILED);

                exchangeFrame(client, targetPeer, "after-failure");
                assertThat(listener.acceptCalls()).isEqualTo(2);
                assertThat(catchThrowable(route::close)).isSameAs(listenerFailure);
                assertPeerClosed(client);
            } finally {
                try {
                    route.close();
                } catch (Exception | Error ignored) {
                    // The first close is asserted above; cleanup is already claimed.
                }
            }
        }
        assertThat(listener.closeCalls()).isEqualTo(1);
    }

    private static GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route(
        ObservationRequirement requirement,
        GatewayListener listener,
        InetSocketAddress target
    ) {
        return GatewayRoute.open(
            CONNECTION,
            target,
            TIMEOUT,
            TIMEOUT,
            requirement,
            observations(),
            interactionRef -> FORWARD,
            new LengthPrefixedProtocolAdapter(),
            LIMITS,
            () -> listener
        );
    }

    private static ConnectionObservations observations() {
        AtomicLong nextSession = new AtomicLong(SessionId.FIRST_VALUE);
        return () -> {
            SessionId sessionId = new SessionId(
                CONNECTION,
                nextSession.getAndIncrement()
            );
            Map<FlowDirection, AtomicLong> ordinals = new EnumMap<>(FlowDirection.class);
            for (FlowDirection direction : FlowDirection.values()) {
                ordinals.put(direction, new AtomicLong(InteractionRef.FIRST_ORDINAL));
            }
            return new InteractionSession() {
                @Override
                public <T> InteractionRef observe(
                    FlowDirection direction,
                    EvidenceCodec<T> codec,
                    T evidence
                ) {
                    return new InteractionRef(
                        sessionId,
                        direction,
                        ordinals.get(direction).getAndIncrement()
                    );
                }
            };
        };
    }

    private static void exchangeFrame(Socket client, Socket targetPeer, String payload)
        throws IOException {
        byte[] frame = LengthPrefixedProtocolAdapter.frame(payload);
        client.getOutputStream().write(frame);
        client.getOutputStream().flush();
        assertThat(targetPeer.getInputStream().readNBytes(frame.length)).isEqualTo(frame);
        targetPeer.getOutputStream().write(frame);
        targetPeer.getOutputStream().flush();
        assertThat(client.getInputStream().readNBytes(frame.length)).isEqualTo(frame);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static void assertPeerClosed(Socket socket) throws IOException {
        try {
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } catch (SocketException closedByRoute) {
            assertThat(closedByRoute).hasMessageNotContaining("timed out");
        }
    }

    private static EffectiveObservationStatus[] statusReads(
        GatewayRoute<?> route,
        int count
    ) {
        EffectiveObservationStatus[] statuses = new EffectiveObservationStatus[count];
        for (int index = 0; index < count; index++) {
            statuses[index] = route.observationStatus();
        }
        return statuses;
    }

    private static void awaitStatus(
        GatewayRoute<?> route,
        EffectiveObservationStatus expected
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (route.observationStatus() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(route.observationStatus()).isEqualTo(expected);
    }
}
