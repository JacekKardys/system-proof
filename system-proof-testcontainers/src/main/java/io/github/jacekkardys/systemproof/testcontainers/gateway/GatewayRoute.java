package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jacekkardys.systemproof.model.ConnectionId;

/** One connection-owned listener and its active transparent TCP sessions. */
final class GatewayRoute implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayRoute.class);

    private final ConnectionId connectionId;
    private final InetSocketAddress target;
    private final int connectTimeoutMillis;
    private final long shutdownTimeoutMillis;
    private final ServerSocket listener;
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private GatewayRoute(
        ConnectionId connectionId,
        InetSocketAddress target,
        Duration connectTimeout,
        Duration shutdownTimeout,
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
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    static GatewayRoute open(
        ConnectionId connectionId,
        InetSocketAddress target,
        Duration connectTimeout,
        Duration shutdownTimeout
    ) {
        ServerSocket listener = null;
        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                0
            ));
            return new GatewayRoute(
                connectionId,
                target,
                requirePositive(connectTimeout, "connectTimeout"),
                requirePositive(shutdownTimeout, "shutdownTimeout"),
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
                    LOG.warn(
                        "InteractionGateway listener failed for connection '{}': {}",
                        connectionId,
                        failure.getClass().getSimpleName()
                    );
                }
                return;
            } catch (IOException failure) {
                if (!closed.get()) {
                    LOG.warn(
                        "InteractionGateway listener failed for connection '{}': {}",
                        connectionId,
                        failure.getClass().getSimpleName()
                    );
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
            Session session = new Session(downstream, upstream);
            try {
                tasks.submit(() -> session.pump(downstream, upstream));
                tasks.submit(() -> session.pump(upstream, downstream));
            } catch (RejectedExecutionException rejected) {
                session.close();
                if (!closed.get()) {
                    throw rejected;
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (!closed.get()) {
                LOG.warn(
                    "InteractionGateway session failed for connection '{}': {}",
                    connectionId,
                    failure.getClass().getSimpleName()
                );
            }
            closeQuietly(downstream);
            closeQuietly(upstream);
        }
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
            // A session is already unavailable; route cleanup reports listener-level failures.
        }
    }

    private final class Session {
        private final Socket downstream;
        private final Socket upstream;
        private final AtomicInteger openDirections = new AtomicInteger(2);
        private final AtomicBoolean sessionClosed = new AtomicBoolean();

        private Session(Socket downstream, Socket upstream) {
            this.downstream = downstream;
            this.upstream = upstream;
        }

        private void pump(Socket source, Socket destination) {
            try {
                transfer(source.getInputStream(), destination.getOutputStream());
                destination.shutdownOutput();
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

        private void close() {
            if (!sessionClosed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(downstream);
            closeQuietly(upstream);
        }
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
}
