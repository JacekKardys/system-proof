package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jacekkardys.systemproof.engine.ForwardingDecision;
import io.github.jacekkardys.systemproof.engine.CorrelationContribution;
import io.github.jacekkardys.systemproof.engine.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.engine.InteractionSession;
import io.github.jacekkardys.systemproof.engine.ObservationStatusProvider;
import io.github.jacekkardys.systemproof.engine.ConnectionObservations;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionRef;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.runtime.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.model.runtime.ObservationRequirement;

/** One connection-owned listener, its active socket pairs, and bounded directional pipelines. */
final class GatewayRoute<E> implements AutoCloseable, ObservationStatusProvider {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayRoute.class);
    private static final int MAXIMUM_READ_CHUNK_BYTES = 8 * 1024;

    private final ConnectionId connectionId;
    private final InetSocketAddress target;
    private final int connectTimeoutMillis;
    private final long shutdownTimeoutMillis;
    private final ObservationRequirement observationRequirement;
    private final ConnectionObservations observations;
    private final InteractionDecisionCoordinator coordinator;
    private final ProtocolAdapter<E> protocolAdapter;
    private final ProtocolLimits protocolLimits;
    private final ServerSocket listener;
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicReference<EffectiveObservationStatus> observationStatus;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private GatewayRoute(
        ConnectionId connectionId,
        InetSocketAddress target,
        Duration connectTimeout,
        Duration shutdownTimeout,
        ObservationRequirement observationRequirement,
        ConnectionObservations observations,
        InteractionDecisionCoordinator coordinator,
        ProtocolAdapter<E> protocolAdapter,
        ProtocolLimits protocolLimits,
        EffectiveObservationStatus initialObservationStatus,
        ServerSocket listener
    ) {
        this.connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        this.target = Objects.requireNonNull(target, "target must not be null");
        connectTimeoutMillis = Math.toIntExact(
            Objects.requireNonNull(connectTimeout, "connectTimeout must not be null").toMillis()
        );
        shutdownTimeoutMillis = Objects.requireNonNull(
            shutdownTimeout,
            "shutdownTimeout must not be null"
        ).toMillis();
        this.observationRequirement = Objects.requireNonNull(
            observationRequirement,
            "observationRequirement must not be null"
        );
        this.observations = Objects.requireNonNull(
            observations,
            "observations must not be null"
        );
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.protocolAdapter = protocolAdapter;
        this.protocolLimits = protocolLimits;
        observationStatus = new AtomicReference<>(Objects.requireNonNull(
            initialObservationStatus,
            "initialObservationStatus must not be null"
        ));
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    static <E> GatewayRoute<E> open(
        ConnectionId connectionId,
        InetSocketAddress target,
        Duration connectTimeout,
        Duration shutdownTimeout,
        ObservationRequirement observationRequirement,
        ConnectionObservations observations,
        InteractionDecisionCoordinator coordinator,
        ProtocolAdapter<E> protocolAdapter,
        ProtocolLimits protocolLimits
    ) {
        EffectiveObservationStatus initialStatus = validateObservationConfiguration(
            observationRequirement,
            protocolAdapter,
            protocolLimits
        );
        ServerSocket listener = null;
        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                0
            ));
            return new GatewayRoute<>(
                connectionId,
                target,
                requirePositive(connectTimeout, "connectTimeout"),
                requirePositive(shutdownTimeout, "shutdownTimeout"),
                observationRequirement,
                observations,
                coordinator,
                protocolAdapter,
                protocolLimits,
                initialStatus,
                listener
            );
        } catch (IOException | RuntimeException failure) {
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw new IllegalStateException(
                "InteractionGateway could not open a listener for connection '"
                    + connectionId + "'",
                failure
            );
        }
    }

    @Override
    public EffectiveObservationStatus observationStatus() {
        return observationStatus.get();
    }

    int listenerPort() {
        return listener.getLocalPort();
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "InteractionGateway route for connection '" + connectionId
                    + "' was started more than once"
            );
        }
        if (closed.get()) {
            throw new IllegalStateException(
                "InteractionGateway route for connection '" + connectionId
                    + "' is already closed"
            );
        }
        tasks.submit(this::acceptConnections);
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = closeSocket(listener, null);
        for (Socket socket : sockets) {
            failure = closeSocket(socket, failure);
        }
        tasks.shutdownNow();
        try {
            if (!tasks.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                failure = accumulate(
                    failure,
                    new IllegalStateException(
                        "InteractionGateway route for connection '" + connectionId
                            + "' did not terminate"
                    )
                );
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = accumulate(failure, interrupted);
        }
        observationStatus.compareAndSet(
            EffectiveObservationStatus.ACTIVE,
            EffectiveObservationStatus.INACTIVE
        );
        if (failure != null) {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }

    private void acceptConnections() {
        while (!closed.get()) {
            try {
                Socket downstream = listener.accept();
                if (register(downstream)) {
                    try {
                        tasks.submit(() -> openSession(downstream));
                    } catch (RejectedExecutionException rejected) {
                        closeQuietly(downstream);
                        if (!closed.get()) {
                            throw rejected;
                        }
                    }
                }
            } catch (SocketException failure) {
                if (!closed.get()) {
                    logListenerFailure(failure);
                }
                return;
            } catch (IOException failure) {
                if (!closed.get()) {
                    logListenerFailure(failure);
                }
                return;
            }
        }
    }

    private void openSession(Socket downstream) {
        Socket upstream = new Socket();
        if (!register(upstream)) {
            closeQuietly(downstream);
            return;
        }
        try {
            downstream.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            upstream.connect(target, connectTimeoutMillis);
            Session session = createSession(downstream, upstream);
            try {
                tasks.submit(() -> session.pump(
                    downstream,
                    upstream,
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    session.consumerToProvider
                ));
                tasks.submit(() -> session.pump(
                    upstream,
                    downstream,
                    FlowDirection.PROVIDER_TO_CONSUMER,
                    session.providerToConsumer
                ));
            } catch (RejectedExecutionException rejected) {
                session.close();
                if (!closed.get()) {
                    throw rejected;
                }
            }
        } catch (ObservationPipelineException failure) {
            failObservation();
            logObservationFailure(failure.stage);
            closeQuietly(downstream);
            closeQuietly(upstream);
            rethrowErrorCause(failure);
        } catch (IOException | RuntimeException failure) {
            if (!closed.get()) {
                LOG.warn(
                    "InteractionGateway session setup failed for connection '{}': {}",
                    connectionId,
                    failure.getClass().getSimpleName()
                );
            }
            closeQuietly(downstream);
            closeQuietly(upstream);
        }
    }

    private Session createSession(Socket downstream, Socket upstream)
        throws ObservationPipelineException {
        EffectiveObservationStatus currentStatus = observationStatus();
        if (currentStatus == EffectiveObservationStatus.DISABLED
            || currentStatus == EffectiveObservationStatus.UNSUPPORTED) {
            return new Session(downstream, upstream, null, null, null, null);
        }
        if (currentStatus != EffectiveObservationStatus.ACTIVE) {
            throw new ObservationPipelineException(
                FailureStage.ADAPTER,
                new IllegalStateException("Observation route is not active")
            );
        }

        InteractionSession interactionSession;
        try {
            interactionSession = Objects.requireNonNull(
                observations.openSession(),
                "Connection observations returned null interaction session"
            );
        } catch (RuntimeException | Error failure) {
            throw new ObservationPipelineException(FailureStage.RECORD, failure);
        }

        ProtocolSession<E> protocolSession;
        EvidenceCodec<E> codec;
        try {
            codec = Objects.requireNonNull(
                protocolAdapter.evidenceCodec(),
                "Protocol adapter returned null evidence codec"
            );
            protocolSession = Objects.requireNonNull(
                protocolAdapter.openSession(protocolLimits),
                "Protocol adapter returned null protocol session"
            );
        } catch (RuntimeException | Error failure) {
            throw new ObservationPipelineException(FailureStage.ADAPTER, failure);
        }

        ProtocolStream<E> consumerToProvider;
        ProtocolStream<E> providerToConsumer;
        try {
            consumerToProvider = Objects.requireNonNull(
                protocolSession.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
                "Protocol session returned null consumer-to-provider stream"
            );
            providerToConsumer = Objects.requireNonNull(
                protocolSession.openStream(FlowDirection.PROVIDER_TO_CONSUMER),
                "Protocol session returned null provider-to-consumer stream"
            );
            if (consumerToProvider == providerToConsumer) {
                throw new IllegalStateException(
                    "Protocol session must create independent stream state per direction"
                );
            }
        } catch (RuntimeException | Error failure) {
            throw new ObservationPipelineException(FailureStage.ADAPTER, failure);
        }

        return new Session(
            downstream,
            upstream,
            interactionSession,
            codec,
            consumerToProvider,
            providerToConsumer
        );
    }

    private boolean register(Socket socket) {
        if (closed.get()) {
            closeQuietly(socket);
            return false;
        }
        sockets.add(socket);
        if (closed.get()) {
            closeQuietly(socket);
            return false;
        }
        return true;
    }

    private void closeQuietly(Socket socket) {
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket is already unavailable; route cleanup reports listener-level failures.
        }
    }

    private void failObservation() {
        EffectiveObservationStatus failedStatus =
            observationRequirement == ObservationRequirement.REQUIRED
                ? EffectiveObservationStatus.FAILED
                : EffectiveObservationStatus.DEGRADED;
        observationStatus.updateAndGet(current ->
            current == EffectiveObservationStatus.ACTIVE ? failedStatus : current
        );
    }

    private void logObservationFailure(FailureStage stage) {
        if (!closed.get()) {
            LOG.warn(
                "InteractionGateway observation failed closed for connection '{}' at stage {}",
                connectionId,
                stage
            );
        }
    }

    private void logListenerFailure(Exception failure) {
        LOG.warn(
            "InteractionGateway listener failed for connection '{}': {}",
            connectionId,
            failure.getClass().getSimpleName()
        );
    }

    private final class Session {
        private final Socket downstream;
        private final Socket upstream;
        private final InteractionSession interactionSession;
        private final EvidenceCodec<E> codec;
        private final ProtocolStream<E> consumerToProvider;
        private final ProtocolStream<E> providerToConsumer;
        private final AtomicInteger openDirections = new AtomicInteger(2);
        private final AtomicBoolean sessionClosed = new AtomicBoolean();

        private Session(
            Socket downstream,
            Socket upstream,
            InteractionSession interactionSession,
            EvidenceCodec<E> codec,
            ProtocolStream<E> consumerToProvider,
            ProtocolStream<E> providerToConsumer
        ) {
            this.downstream = downstream;
            this.upstream = upstream;
            this.interactionSession = interactionSession;
            this.codec = codec;
            this.consumerToProvider = consumerToProvider;
            this.providerToConsumer = providerToConsumer;
        }

        private void pump(
            Socket source,
            Socket destination,
            FlowDirection direction,
            ProtocolStream<E> protocolStream
        ) {
            try {
                if (protocolStream == null) {
                    transfer(source.getInputStream(), destination.getOutputStream());
                } else {
                    observeBeforeForward(
                        source.getInputStream(),
                        destination.getOutputStream(),
                        direction,
                        protocolStream
                    );
                }
                destination.shutdownOutput();
            } catch (ProtocolAdapterException failure) {
                failObservation();
                if (!closed.get() && !sessionClosed.get()) {
                    LOG.warn(
                        "InteractionGateway protocol observation failed closed for connection '{}' with classification {}",
                        connectionId,
                        failure.kind()
                    );
                }
                close();
                return;
            } catch (ObservationPipelineException failure) {
                failObservation();
                logObservationFailure(failure.stage);
                close();
                rethrowErrorCause(failure);
                return;
            } catch (IOException failure) {
                if (!closed.get() && !sessionClosed.get()) {
                    LOG.debug(
                        "InteractionGateway session ended for connection '{}': {}",
                        connectionId,
                        failure.getClass().getSimpleName()
                    );
                }
                close();
                return;
            }
            if (openDirections.decrementAndGet() == 0) {
                close();
            }
        }

        private void observeBeforeForward(
            InputStream source,
            OutputStream destination,
            FlowDirection direction,
            ProtocolStream<E> protocolStream
        ) throws IOException, ProtocolAdapterException, ObservationPipelineException {
            PendingBytes pending = new PendingBytes(protocolLimits.maximumBufferedBytes());
            byte[] chunk = new byte[Math.min(
                MAXIMUM_READ_CHUNK_BYTES,
                protocolLimits.maximumBufferedBytes()
            )];
            while (true) {
                int available = protocolLimits.maximumBufferedBytes() - pending.size();
                if (available == 0) {
                    throw new ProtocolAdapterException(
                        ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                        "Directional protocol buffer limit was reached"
                    );
                }
                int read = source.read(chunk, 0, Math.min(chunk.length, available));
                if (read == -1) {
                    break;
                }
                pending.append(chunk, read);
                drainCompleteUnits(pending, destination, direction, protocolStream);
            }
            try {
                protocolStream.endOfInput(pending.view());
            } catch (ProtocolAdapterException failure) {
                throw failure;
            } catch (RuntimeException | Error failure) {
                throw new ObservationPipelineException(FailureStage.ADAPTER, failure);
            }
            if (pending.size() != 0) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "Protocol stream accepted incomplete input at EOF"
                );
            }
            destination.flush();
        }

        private void drainCompleteUnits(
            PendingBytes pending,
            OutputStream destination,
            FlowDirection direction,
            ProtocolStream<E> protocolStream
        ) throws IOException, ProtocolAdapterException, ObservationPipelineException {
            while (true) {
                ProtocolDecodeResult<E> decoded;
                try {
                    decoded = Objects.requireNonNull(
                        protocolStream.decode(pending.view()),
                        "Protocol stream returned null decode result"
                    );
                } catch (ProtocolAdapterException failure) {
                    throw failure;
                } catch (RuntimeException | Error failure) {
                    throw new ObservationPipelineException(FailureStage.ADAPTER, failure);
                }
                if (decoded instanceof ProtocolDecodeResult.NeedMoreData<E>) {
                    return;
                }
                ProtocolUnit<E> unit =
                    ((ProtocolDecodeResult.Complete<E>) decoded).unit();
                byte[] originalBytes = unit.originalBytes();
                if (originalBytes.length > protocolLimits.maximumFrameBytes()) {
                    throw new ProtocolAdapterException(
                        ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                        "Decoded protocol unit exceeded the frame limit"
                    );
                }
                pending.removeExactPrefix(originalBytes);

                InteractionRef interactionRef;
                try {
                    interactionRef = Objects.requireNonNull(
                        interactionSession.observe(direction, codec, unit.evidence()),
                        "Interaction session returned null interaction reference"
                    );
                } catch (RuntimeException | Error failure) {
                    throw new ObservationPipelineException(FailureStage.RECORD, failure);
                }

                try {
                    for (CorrelationContribution<?> contribution
                        : unit.correlationContributions()) {
                        interactionSession.correlate(interactionRef, contribution);
                    }
                } catch (RuntimeException | Error failure) {
                    throw new ObservationPipelineException(
                        FailureStage.CORRELATION,
                        failure
                    );
                }

                ForwardingDecision decision;
                try {
                    decision = Objects.requireNonNull(
                        coordinator.decide(interactionRef),
                        "Interaction coordinator returned null decision"
                    );
                } catch (RuntimeException | Error failure) {
                    throw new ObservationPipelineException(FailureStage.DECISION, failure);
                }
                if (decision != ForwardingDecision.FORWARD) {
                    throw new ObservationPipelineException(
                        FailureStage.DECISION,
                        new IllegalStateException("Unsupported forwarding decision")
                    );
                }
                destination.write(originalBytes);
                destination.flush();
            }
        }

        private void close() {
            if (!sessionClosed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(downstream);
            closeQuietly(upstream);
        }
    }

    private static final class PendingBytes {
        private final int maximumBytes;
        private byte[] bytes;
        private int size;

        private PendingBytes(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            bytes = new byte[Math.min(MAXIMUM_READ_CHUNK_BYTES, maximumBytes)];
        }

        private int size() {
            return size;
        }

        private void append(byte[] source, int length) throws ProtocolAdapterException {
            if (length < 0 || length > source.length) {
                throw new IllegalArgumentException("Invalid append length");
            }
            if (length > maximumBytes - size) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                    "Directional protocol buffer limit was exceeded"
                );
            }
            ensureCapacity(size + length);
            System.arraycopy(source, 0, bytes, size, length);
            size += length;
        }

        private ByteBuffer view() {
            return ByteBuffer.wrap(bytes, 0, size).asReadOnlyBuffer();
        }

        private void removeExactPrefix(byte[] prefix) throws ProtocolAdapterException {
            if (prefix.length > size) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "Protocol unit exceeded available buffered bytes"
                );
            }
            for (int index = 0; index < prefix.length; index++) {
                if (bytes[index] != prefix[index]) {
                    throw new ProtocolAdapterException(
                        ProtocolFailureKind.DESYNCHRONIZATION,
                        "Protocol unit did not preserve the original byte prefix"
                    );
                }
            }
            int remaining = size - prefix.length;
            System.arraycopy(bytes, prefix.length, bytes, 0, remaining);
            Arrays.fill(bytes, remaining, size, (byte) 0);
            size = remaining;
        }

        private void ensureCapacity(int required) {
            if (required <= bytes.length) {
                return;
            }
            int doubled = bytes.length > maximumBytes / 2
                ? maximumBytes
                : bytes.length * 2;
            int expanded = Math.min(maximumBytes, Math.max(required, doubled));
            bytes = Arrays.copyOf(bytes, expanded);
        }
    }

    private static EffectiveObservationStatus validateObservationConfiguration(
        ObservationRequirement observationRequirement,
        ProtocolAdapter<?> protocolAdapter,
        ProtocolLimits protocolLimits
    ) {
        Objects.requireNonNull(
            observationRequirement,
            "observationRequirement must not be null"
        );
        return switch (observationRequirement) {
            case DISABLED -> {
                if (protocolAdapter != null) {
                    throw new IllegalArgumentException(
                        "Disabled observation must use the transparent gateway path"
                    );
                }
                yield EffectiveObservationStatus.DISABLED;
            }
            case OPTIONAL -> {
                if (protocolAdapter == null) {
                    yield EffectiveObservationStatus.UNSUPPORTED;
                }
                Objects.requireNonNull(protocolLimits, "protocolLimits must not be null");
                yield EffectiveObservationStatus.ACTIVE;
            }
            case REQUIRED -> {
                if (protocolAdapter == null) {
                    throw new IllegalStateException(
                        "Required observation has no protocol adapter"
                    );
                }
                Objects.requireNonNull(protocolLimits, "protocolLimits must not be null");
                yield EffectiveObservationStatus.ACTIVE;
            }
        };
    }

    private static void transfer(InputStream source, OutputStream destination)
        throws IOException {
        source.transferTo(destination);
        destination.flush();
    }

    private static Duration requirePositive(Duration value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(description + " must be positive");
        }
        return value;
    }

    private static Throwable closeSocket(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
            return failure;
        } catch (Exception | Error closeFailure) {
            return accumulate(failure, closeFailure);
        }
    }

    private static Throwable accumulate(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrowErrorCause(ObservationPipelineException failure) {
        if (failure.getCause() instanceof Error error) {
            throw error;
        }
    }

    private enum FailureStage {
        ADAPTER,
        RECORD,
        CORRELATION,
        DECISION
    }

    private static final class ObservationPipelineException extends Exception {
        private final FailureStage stage;

        private ObservationPipelineException(FailureStage stage, Throwable cause) {
            super(Objects.requireNonNull(cause, "cause must not be null"));
            this.stage = Objects.requireNonNull(stage, "stage must not be null");
        }
    }
}
