package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static io.github.jacekkardys.systemproof.construction.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.construction.ComponentPortFactory.provides;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;
import static io.github.jacekkardys.systemproof.model.topology.Contract.contract;
import static io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding.binding;
import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.engine.execution.ConnectionRouting;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.engine.execution.EnvironmentStartException;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentType;
import io.github.jacekkardys.systemproof.model.runtime.ConnectionState;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.runtime.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.runtime.ObservationRequirement;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;
import io.github.jacekkardys.systemproof.model.runtime.RoutingMode;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.value.Secret;

class InteractionGatewayTest {
    private static final ComponentType CLIENT = ComponentType.of("gateway-client");
    private static final ComponentType SERVER = ComponentType.of("gateway-server");
    private static final Contract<CommandEndpoint> COMMAND =
        contract("command", CommandEndpoint.class);
    private static final Contract<SessionEndpoint> SESSION =
        contract("session", SessionEndpoint.class);

    @Test
    void shouldExposeActiveRequiredAndUnsupportedOptionalObservationStatuses()
        throws Exception {
        List<InetSocketAddress> listenerAddresses = new ArrayList<>();
        Server server = server(new ArrayList<>(), new AtomicInteger());
        Client client = new Client((component, context) -> {
            Client typed = (Client) component;
            return ComponentRuntime.<ResolvedRoutes>runtime()
                .operations(new ResolvedRoutes(
                    context.resolve(typed.command),
                    context.resolve(typed.session)
                ))
                .build();
        });
        InteractionGateway gateway = new InteractionGateway(port -> {});
        EnvironmentBuilder builder = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.command, server.command)
            .connect(client.session, server.session);
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            ObservationRequirement.REQUIRED,
            gateway.tcp(
                commandAdapter(
                    "required-route",
                    listenerAddresses,
                    new ArrayList<>()
                ),
                new LengthPrefixedProtocolAdapter(),
                new ProtocolLimits(128, 256)
            )
        ).withRoute(
            SESSION,
            ObservationRequirement.OPTIONAL,
            gateway.tcp(sessionAdapter(
                "optional-route",
                listenerAddresses,
                new ArrayList<>()
            ))
        );
        RoutedEnvironment environment = routedEnvironment(builder, routing);

        try {
            environment.start();

            assertThat(environment.runtimeConnections())
                .extracting(
                    snapshot -> snapshot.observationRequirement(),
                    snapshot -> snapshot.effectiveObservationStatus()
                )
                .containsExactly(
                    tuple(
                        ObservationRequirement.REQUIRED,
                        EffectiveObservationStatus.ACTIVE
                    ),
                    tuple(
                        ObservationRequirement.OPTIONAL,
                        EffectiveObservationStatus.UNSUPPORTED
                    )
                );

            try (Socket socket = connect(listenerAddresses.getFirst())) {
                socket.getOutputStream().write(
                    LengthPrefixedProtocolAdapter.control(
                        LengthPrefixedProtocolAdapter.UNSUPPORTED_ENCRYPTION
                    )
                );
                socket.getOutputStream().flush();
                assertPeerClosed(socket);
            }
            assertThat(environment.runtimeConnections())
                .extracting(snapshot -> snapshot.effectiveObservationStatus())
                .containsExactly(
                    EffectiveObservationStatus.FAILED,
                    EffectiveObservationStatus.UNSUPPORTED
                );
        } finally {
            environment.close();
        }
        listenerAddresses.forEach(InteractionGatewayTest::assertPortCanBeRebound);
    }

    @Test
    void shouldResolveEnvironmentProofSubjectThroughTheProductionGatewayPath()
        throws Exception {
        List<InetSocketAddress> listenerAddresses = new ArrayList<>();
        AtomicReference<FrameServer> frameServer = new AtomicReference<>();
        Server server = correlationServer(frameServer);
        Client client = new Client((component, context) -> {
            Client typed = (Client) component;
            return ComponentRuntime.<ResolvedRoutes>runtime()
                .operations(new ResolvedRoutes(
                    context.resolve(typed.command),
                    context.resolve(typed.session)
                ))
                .build();
        });
        InteractionGateway gateway = new InteractionGateway(port -> {});
        EnvironmentBuilder builder = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.command, server.command)
            .connect(client.session, server.session);
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            ObservationRequirement.REQUIRED,
            gateway.tcp(
                commandAdapter(
                    "correlation-route",
                    listenerAddresses,
                    new ArrayList<>()
                ),
                LengthPrefixedProtocolAdapter.correlating(),
                new ProtocolLimits(128, 256)
            )
        ).withRoute(
            SESSION,
            gateway.tcp(sessionAdapter(
                "session-route",
                listenerAddresses,
                new ArrayList<>()
            ))
        );
        RoutedEnvironment environment = routedEnvironment(builder, routing);
        String payload = "production-registry-correlation";
        CorrelationKey key = LengthPrefixedProtocolAdapter.correlationKey(payload);
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.proofSubjects().arm(subject, key);

        try {
            environment.start();
            byte[] frame = LengthPrefixedProtocolAdapter.frame(payload);
            try (Socket socket = connect(listenerAddresses.getFirst())) {
                socket.getOutputStream().write(frame);
                socket.getOutputStream().flush();
                assertThat(frameServer.get().awaitPayload())
                    .isEqualTo(payload.getBytes(UTF_8));
            }

            CorrelationResult<
                LengthPrefixedProtocolAdapter.FrameNativeReference
            > result = environment.proofSubjects().correlation(
                subject,
                key,
                LengthPrefixedProtocolAdapter.NATIVE_REFERENCE_CODEC
            );
            assertThat(result).isInstanceOf(CorrelationResult.Unique.class);
            CorrelationResult.Unique<
                LengthPrefixedProtocolAdapter.FrameNativeReference
            > unique = (CorrelationResult.Unique<
                LengthPrefixedProtocolAdapter.FrameNativeReference
            >) result;
            assertThat(unique.nativeReference().direction())
                .isEqualTo(FlowDirection.CONSUMER_TO_PROVIDER);
            assertThat(unique.nativeReference().payloadBytes())
                .isEqualTo(payload.getBytes(UTF_8).length);
            assertThat(unique.nativeReference().payloadSha256())
                .isEqualTo(LengthPrefixedProtocolAdapter.sha256(
                    payload.getBytes(UTF_8)
                ));

            List<ScenarioEvent> events = environment.journalSnapshot().entries().stream()
                .map(entry -> entry.event())
                .toList();
            InteractionObservationEvent observation = events.stream()
                .filter(InteractionObservationEvent.class::isInstance)
                .map(InteractionObservationEvent.class::cast)
                .findFirst()
                .orElseThrow();
            CorrelationCandidateEvent candidate = events.stream()
                .filter(CorrelationCandidateEvent.class::isInstance)
                .map(CorrelationCandidateEvent.class::cast)
                .findFirst()
                .orElseThrow();
            assertThat(candidate.proofSubject()).contains(subject);
            assertThat(candidate.interactionRef()).isEqualTo(observation.interactionRef());
            assertThat(unique.interactionRef()).isEqualTo(observation.interactionRef());
            assertThat(events.indexOf(candidate)).isGreaterThan(events.indexOf(observation));
        } finally {
            environment.close();
        }
        listenerAddresses.forEach(InteractionGatewayTest::assertPortCanBeRebound);
    }

    @Test
    void shouldKeepDistinctTypedRoutesAndLongLivedSessionsConnectionOwned() throws Exception {
        List<String> lifecycle = new ArrayList<>();
        List<InetSocketAddress> listenerAddresses = new ArrayList<>();
        AtomicInteger providerCloses = new AtomicInteger();
        Server server = server(lifecycle, providerCloses);
        Client client = new Client((component, context) -> {
            Client typed = (Client) component;
            ResolvedRoutes routes = new ResolvedRoutes(
                context.resolve(typed.command),
                context.resolve(typed.session)
            );
            assertThat(exchangeUnchecked(listenerAddresses.get(0), "startup-check"))
                .isEqualTo("command-provider:startup-check");
            assertThat(exchangeUnchecked(listenerAddresses.get(1), "startup-check"))
                .isEqualTo("session-provider:startup-check");
            lifecycle.add("consumer-start");
            return ComponentRuntime.<ResolvedRoutes>runtime()
                .operations(routes)
                .build();
        });
        InteractionGateway gateway = new InteractionGateway(
            port -> lifecycle.add("expose:" + port)
        );
        TcpEndpointAdapter<CommandEndpoint> commands =
            commandAdapter("command-route", listenerAddresses, lifecycle);
        TcpEndpointAdapter<SessionEndpoint> sessions =
            sessionAdapter("session-route", listenerAddresses, lifecycle);
        RoutedEnvironment environment = environment(
            server,
            client,
            gateway,
            commands,
            sessions
        );
        Socket longLivedSession = null;

        try {
            environment.start();

            assertThat(lifecycle)
                .hasSize(6)
                .startsWith("provider-start")
                .endsWith("consumer-start");
            assertThat(lifecycle.get(1)).startsWith("expose:");
            assertThat(lifecycle.get(2)).startsWith("command-route:");
            assertThat(lifecycle.get(3)).startsWith("expose:");
            assertThat(lifecycle.get(4)).startsWith("session-route:");
            assertThat(listenerAddresses).hasSize(2).doesNotHaveDuplicates();
            assertThat(environment.runtimeConnections())
                .hasSize(2)
                .allSatisfy(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(ConnectionState.RUNNING);
                    assertThat(snapshot.routingMode()).isEqualTo(RoutingMode.ROUTED);
                    assertThat(snapshot.observationRequirement())
                        .isEqualTo(ObservationRequirement.DISABLED);
                    assertThat(snapshot.effectiveObservationStatus())
                        .isEqualTo(EffectiveObservationStatus.DISABLED);
                });

            ResolvedRoutes resolved = environment.routes(client);
            assertThat(resolved.command().host())
                .isEqualTo(InteractionGateway.CONTAINER_HOST);
            assertThat(resolved.session().host())
                .isEqualTo(InteractionGateway.CONTAINER_HOST);
            assertThat(resolved.command().port())
                .isNotEqualTo(resolved.session().port());
            assertThat(environment.diagnostics().content())
                .doesNotContain(
                    "session-secret",
                    InteractionGateway.CONTAINER_HOST,
                    InteractionGateway.TEST_HOST
                );
            listenerAddresses.forEach(address ->
                assertThat(environment.diagnostics().content())
                    .doesNotContain(Integer.toString(address.getPort()))
            );

            assertThat(exchange(listenerAddresses.get(0), "request"))
                .isEqualTo("command-provider:request");
            longLivedSession = connect(listenerAddresses.get(1));
            assertThat(exchange(longLivedSession, "bind")).isEqualTo("session-provider:bind");
            assertThat(exchange(longLivedSession, "submit-1"))
                .isEqualTo("session-provider:submit-1");
            assertThat(exchange(longLivedSession, "submit-2"))
                .isEqualTo("session-provider:submit-2");

            environment.close();

            assertPeerClosed(longLivedSession);
            assertThat(providerCloses).hasValue(1);
            assertThat(environment.runtimeConnections())
                .allSatisfy(snapshot ->
                    assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPED)
                );
            listenerAddresses.forEach(InteractionGatewayTest::assertPortCanBeRebound);
        } finally {
            if (longLivedSession != null) {
                longLivedSession.close();
            }
            environment.close();
        }
    }

    @Test
    void shouldReleaseBothRoutesAfterConsumerStartupFails() {
        List<InetSocketAddress> listenerAddresses = new ArrayList<>();
        AtomicInteger providerCloses = new AtomicInteger();
        IllegalStateException startupFailure =
            new IllegalStateException("Injected consumer startup failure");
        Server server = server(new ArrayList<>(), providerCloses);
        Client client = new Client((component, context) -> {
            Client typed = (Client) component;
            context.resolve(typed.command);
            context.resolve(typed.session);
            throw startupFailure;
        });
        InteractionGateway gateway = new InteractionGateway(port -> {});
        RoutedEnvironment environment = environment(
            server,
            client,
            gateway,
            commandAdapter("command-route", listenerAddresses, new ArrayList<>()),
            sessionAdapter("session-route", listenerAddresses, new ArrayList<>())
        );

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause()).isSameAs(startupFailure);
        assertThat(listenerAddresses).hasSize(2);
        assertThat(providerCloses).hasValue(1);
        assertThat(environment.runtimeConnections())
            .allSatisfy(snapshot -> {
                assertThat(snapshot.state()).isEqualTo(ConnectionState.STOPPED);
                assertThat(snapshot.directTargetAvailable()).isFalse();
                assertThat(snapshot.consumerTargetAvailable()).isFalse();
            });
        listenerAddresses.forEach(InteractionGatewayTest::assertPortCanBeRebound);
    }

    @Test
    void shouldFailFastAndReleaseTheListenerWhenHostRoutingIsUnsupported() {
        AtomicInteger exposedPort = new AtomicInteger();
        AtomicInteger providerCloses = new AtomicInteger();
        AtomicInteger consumerStarts = new AtomicInteger();
        String diagnosticSecret = "unsupported-host-routing-secret";
        IllegalStateException exposureFailure =
            new IllegalStateException("Host forwarding unavailable: " + diagnosticSecret);
        Server server = server(new ArrayList<>(), providerCloses);
        Client client = new Client((component, context) -> {
            consumerStarts.incrementAndGet();
            throw new AssertionError("Consumer must not start");
        });
        InteractionGateway gateway = new InteractionGateway(port -> {
            exposedPort.set(port);
            throw exposureFailure;
        });
        RoutedEnvironment environment = environment(
            server,
            client,
            gateway,
            commandAdapter("command-route", new ArrayList<>(), new ArrayList<>()),
            sessionAdapter("session-route", new ArrayList<>(), new ArrayList<>())
        );

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause())
            .isInstanceOf(IllegalStateException.class)
            .hasCause(exposureFailure)
            .hasMessageContaining(
                "InteractionGateway could not expose its listener",
                environment.connections().getFirst().id().toString(),
                "Testcontainers host routing",
                "Docker Desktop",
                "host.testcontainers.internal"
            );
        assertThat(exposedPort).hasPositiveValue();
        assertThat(consumerStarts).hasValue(0);
        assertThat(providerCloses).hasValue(1);
        assertThat(thrown.diagnostics().content())
            .contains(
                "Route preparation failed for connection '"
                    + environment.connections().getFirst().id() + "'"
            )
            .doesNotContain(
                diagnosticSecret,
                "session-secret",
                Integer.toString(exposedPort.get())
            );
        assertPortCanBeRebound(exposedPort.get());
    }

    @Test
    void shouldReleaseTheEarlierRouteWhenLaterHostExposureFails() {
        List<Integer> exposedPorts = new ArrayList<>();
        AtomicInteger providerCloses = new AtomicInteger();
        AtomicInteger consumerStarts = new AtomicInteger();
        IllegalStateException exposureFailure =
            new IllegalStateException("Second host forwarding unavailable");
        Server server = server(new ArrayList<>(), providerCloses);
        Client client = new Client((component, context) -> {
            consumerStarts.incrementAndGet();
            throw new AssertionError("Consumer must not start");
        });
        InteractionGateway gateway = new InteractionGateway(port -> {
            exposedPorts.add(port);
            if (exposedPorts.size() == 2) {
                throw exposureFailure;
            }
        });
        RoutedEnvironment environment = environment(
            server,
            client,
            gateway,
            commandAdapter("command-route", new ArrayList<>(), new ArrayList<>()),
            sessionAdapter("session-route", new ArrayList<>(), new ArrayList<>())
        );

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause())
            .isInstanceOf(IllegalStateException.class)
            .hasCause(exposureFailure)
            .hasMessageContaining(environment.connections().get(1).id().toString());
        assertThat(exposedPorts).hasSize(2).doesNotHaveDuplicates();
        assertThat(consumerStarts).hasValue(0);
        assertThat(providerCloses).hasValue(1);
        exposedPorts.forEach(InteractionGatewayTest::assertPortCanBeRebound);
    }

    @Test
    void shouldRejectAnEndpointAdapterThatBypassesTheGatewayListener() {
        AtomicInteger exposedPort = new AtomicInteger();
        AtomicInteger providerCloses = new AtomicInteger();
        AtomicInteger consumerStarts = new AtomicInteger();
        Server server = server(new ArrayList<>(), providerCloses);
        Client client = new Client((component, context) -> {
            consumerStarts.incrementAndGet();
            throw new AssertionError("Consumer must not start");
        });
        InteractionGateway gateway = new InteractionGateway(exposedPort::set);
        TcpEndpointAdapter<CommandEndpoint> bypassingAdapter = endpoint(
            value -> new InetSocketAddress(value.host(), value.port()),
            (value, host, port) -> value
        );
        RoutedEnvironment environment = environment(
            server,
            client,
            gateway,
            bypassingAdapter,
            sessionAdapter("session-route", new ArrayList<>(), new ArrayList<>())
        );

        EnvironmentStartException thrown = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(thrown.getCause())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Replacement endpoint must use the requested TCP address");
        assertThat(exposedPort).hasPositiveValue();
        assertThat(consumerStarts).hasValue(0);
        assertThat(providerCloses).hasValue(1);
        assertPortCanBeRebound(exposedPort.get());
    }

    private static RoutedEnvironment environment(
        Server server,
        Client client,
        InteractionGateway gateway,
        TcpEndpointAdapter<CommandEndpoint> commands,
        TcpEndpointAdapter<SessionEndpoint> sessions
    ) {
        EnvironmentBuilder builder = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.command, server.command)
            .connect(client.session, server.session);
        ConnectionRouting routing = ConnectionRouting.routed(
            COMMAND,
            gateway.tcp(commands)
        ).withRoute(
            SESSION,
            gateway.tcp(sessions)
        );
        return routedEnvironment(builder, routing);
    }

    private static Server server(
        List<String> lifecycle,
        AtomicInteger providerCloses
    ) {
        return server(
            lifecycle,
            providerCloses,
            () -> LineServer.open("command-provider:")
        );
    }

    private static Server correlationServer(
        AtomicReference<FrameServer> frameServerReference
    ) {
        return server(
            new ArrayList<>(),
            new AtomicInteger(),
            () -> {
                FrameServer frameServer = FrameServer.open();
                frameServerReference.set(frameServer);
                return frameServer;
            }
        );
    }

    private static Server server(
        List<String> lifecycle,
        AtomicInteger providerCloses,
        Supplier<TestServer> commandServerFactory
    ) {
        return new Server((component, context) -> {
            TestServer commandServer = commandServerFactory.get();
            LineServer sessionServer;
            try {
                sessionServer = LineServer.open("session-provider:");
            } catch (RuntimeException failure) {
                try {
                    commandServer.close();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            lifecycle.add("provider-start");
            return ComponentRuntime.<Void>runtime(() -> {
                Throwable failure = null;
                try {
                    sessionServer.close();
                } catch (Exception closeFailure) {
                    failure = closeFailure;
                }
                try {
                    commandServer.close();
                } catch (Exception closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
                providerCloses.incrementAndGet();
                if (failure instanceof Exception exception) {
                    throw exception;
                }
            })
                .provides(
                    ((Server) component).command,
                    binding(
                        new CommandEndpoint("127.0.0.1", commandServer.port()),
                        new CommandEndpoint("127.0.0.1", commandServer.port())
                    )
                )
                .provides(
                    ((Server) component).session,
                    binding(
                        new SessionEndpoint(
                            "127.0.0.1",
                            sessionServer.port(),
                            Secret.secret("session-secret")
                        ),
                        new SessionEndpoint(
                            "127.0.0.1",
                            sessionServer.port(),
                            Secret.secret("session-secret")
                        )
                    )
                )
                .build();
        });
    }

    private static TcpEndpointAdapter<CommandEndpoint> commandAdapter(
        String event,
        List<InetSocketAddress> listenerAddresses,
        List<String> lifecycle
    ) {
        return endpoint(
            value -> new InetSocketAddress(value.host(), value.port()),
            (value, host, port) -> {
                recordListener(event, host, port, listenerAddresses, lifecycle);
                return new CommandEndpoint(host, port);
            }
        );
    }

    private static TcpEndpointAdapter<SessionEndpoint> sessionAdapter(
        String event,
        List<InetSocketAddress> listenerAddresses,
        List<String> lifecycle
    ) {
        return endpoint(
            value -> new InetSocketAddress(value.host(), value.port()),
            (value, host, port) -> {
                recordListener(event, host, port, listenerAddresses, lifecycle);
                return new SessionEndpoint(host, port, value.password());
            }
        );
    }

    private static void recordListener(
        String event,
        String host,
        int port,
        List<InetSocketAddress> listenerAddresses,
        List<String> lifecycle
    ) {
        if (InteractionGateway.TEST_HOST.equals(host)) {
            listenerAddresses.add(new InetSocketAddress(host, port));
            lifecycle.add(event + ":" + port);
        }
    }

    private static String exchange(InetSocketAddress address, String request)
        throws IOException {
        try (Socket socket = connect(address)) {
            return exchange(socket, request);
        }
    }

    private static String exchangeUnchecked(InetSocketAddress address, String request) {
        try {
            return exchange(address, request);
        } catch (IOException failure) {
            throw new AssertionError(
                "Gateway listener was unavailable during consumer startup",
                failure
            );
        }
    }

    private static Socket connect(InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        socket.connect(address, 2_000);
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static String exchange(Socket socket, String request) throws IOException {
        BufferedWriter output = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), UTF_8)
        );
        output.write(request);
        output.newLine();
        output.flush();
        return new BufferedReader(
            new InputStreamReader(socket.getInputStream(), UTF_8)
        ).readLine();
    }

    private static void assertPeerClosed(Socket socket) throws IOException {
        try {
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } catch (SocketException closedByPeer) {
            assertThat(closedByPeer).hasMessageNotContaining("timed out");
        }
    }

    private static void assertPortCanBeRebound(InetSocketAddress address) {
        assertPortCanBeRebound(address.getPort());
    }

    private static void assertPortCanBeRebound(int port) {
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                port
            ));
            assertThat(rebound.isBound()).isTrue();
        } catch (IOException failure) {
            throw new AssertionError("Listener port was not released", failure);
        }
    }

    private record CommandEndpoint(String host, int port) {}

    private record SessionEndpoint(
        String host,
        int port,
        Secret<String> password
    ) {}

    private record ResolvedRoutes(
        CommandEndpoint command,
        SessionEndpoint session
    ) {}

    private record EmptyConfig() implements RuntimeConfig {}

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
        }
    }

    private enum Session implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "session";
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

    private enum Smpp implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "smpp";
        }

        @Override
        public String scheme() {
            return "smpp";
        }
    }

    private static final class Client
        extends AbstractComponent<EmptyConfig, ResolvedRoutes> {

        private final RequiredPort<CommandEndpoint> command;
        private final RequiredPort<SessionEndpoint> session;

        private Client(ComponentDriver<EmptyConfig, ResolvedRoutes> driver) {
            super(
                ComponentId.component(CLIENT),
                new EmptyConfig(),
                ResolvedRoutes.class,
                driver
            );
            command = requiresAtStartup(this,
                "command",
                COMMAND,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
            session = requiresAtStartup(this,
                "session",
                SESSION,
                Session.INSTANCE,
                Smpp.INSTANCE
            );
        }
    }

    private static final class Server extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<CommandEndpoint> command;
        private final ProvidedPort<SessionEndpoint> session;

        private Server(ComponentDriver<EmptyConfig, Void> driver) {
            super(ComponentId.component(SERVER), new EmptyConfig(), Void.class, driver);
            command = provides(this, "command", COMMAND, Invocation.INSTANCE, Http.INSTANCE);
            session = provides(this, "session", SESSION, Session.INSTANCE, Smpp.INSTANCE);
        }
    }

    private static RoutedEnvironment routedEnvironment(EnvironmentBuilder builder, ConnectionRouting routing) {
        return builder.build((topology, logging) ->
            new RoutedEnvironment(topology, logging, routing)
        );
    }

    private static final class RoutedEnvironment extends Environment {
        private RoutedEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, ConnectionRouting routing) {
            super(topology, logging, routing);
        }

        private ResolvedRoutes routes(Client client) {
            return operations(client);
        }
    }

    private interface TestServer extends AutoCloseable {
        int port();
    }

    private static final class FrameServer implements TestServer {
        private final ServerSocket listener;
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch received = new CountDownLatch(1);
        private final AtomicReference<byte[]> payload = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private FrameServer(ServerSocket listener) {
            this.listener = listener;
            tasks.submit(this::accept);
        }

        private static FrameServer open() {
            try {
                ServerSocket listener = new ServerSocket();
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                return new FrameServer(listener);
            } catch (IOException failure) {
                throw new IllegalStateException(
                    "Could not open test frame server",
                    failure
                );
            }
        }

        @Override
        public int port() {
            return listener.getLocalPort();
        }

        private byte[] awaitPayload() {
            try {
                if (!received.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                        "Gateway did not forward the correlated frame"
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                    "Interrupted while waiting for the correlated frame",
                    interrupted
                );
            }
            if (failure.get() != null) {
                throw new AssertionError(
                    "Frame server could not receive the correlated frame",
                    failure.get()
                );
            }
            return payload.get().clone();
        }

        private void accept() {
            try (
                Socket client = listener.accept();
                DataInputStream input = new DataInputStream(client.getInputStream())
            ) {
                int payloadBytes = input.readInt();
                byte[] receivedPayload = input.readNBytes(payloadBytes);
                if (receivedPayload.length != payloadBytes) {
                    throw new EOFException(
                        "Frame ended before its declared payload length"
                    );
                }
                payload.set(receivedPayload);
            } catch (Throwable receiveFailure) {
                failure.set(receiveFailure);
            } finally {
                received.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            tasks.close();
        }
    }

    private static final class LineServer implements TestServer {
        private final String prefix;
        private final ServerSocket listener;
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

        private LineServer(String prefix, ServerSocket listener) {
            this.prefix = prefix;
            this.listener = listener;
            tasks.submit(this::accept);
        }

        private static LineServer open(String prefix) {
            try {
                ServerSocket listener = new ServerSocket();
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                return new LineServer(prefix, listener);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not open test line server", failure);
            }
        }

        @Override
        public int port() {
            return listener.getLocalPort();
        }

        private void accept() {
            try {
                while (!listener.isClosed()) {
                    Socket client = listener.accept();
                    clients.add(client);
                    tasks.submit(() -> serve(client));
                }
            } catch (IOException ignored) {
                // Closing the listener terminates the test server.
            }
        }

        private void serve(Socket client) {
            try (
                client;
                BufferedReader input = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), UTF_8)
                );
                BufferedWriter output = new BufferedWriter(
                    new OutputStreamWriter(client.getOutputStream(), UTF_8)
                )
            ) {
                String line;
                while ((line = input.readLine()) != null) {
                    output.write(prefix);
                    output.write(line);
                    output.newLine();
                    output.flush();
                }
            } catch (IOException ignored) {
                // Route cleanup closes active test connections.
            } finally {
                clients.remove(client);
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            clients.forEach(client -> {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // The client is already closed.
                }
            });
            tasks.close();
        }
    }
}
