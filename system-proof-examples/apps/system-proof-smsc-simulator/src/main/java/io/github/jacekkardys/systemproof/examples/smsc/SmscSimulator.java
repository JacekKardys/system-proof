package io.github.jacekkardys.systemproof.examples.smsc;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.BindType;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.BindRequest;
import org.jsmpp.session.Session;
import org.jsmpp.session.connection.Connection;
import org.jsmpp.session.connection.ServerConnection;
import org.jsmpp.session.connection.socket.ServerSocketConnectionFactory;

public final class SmscSimulator implements Closeable {
    private static final long BIND_TIMEOUT_MS = 30_000;
    private static final long WRITE_TIMEOUT_SECONDS = 10;
    private static final Duration SESSION_WAIT_TIMEOUT = Duration.ofSeconds(10);

    private final int port;
    private final String expectedSystemId;
    private final String expectedPassword;
    private final EventJournal journal = new EventJournal();
    private final Map<String, BoundSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object sessionMonitor = new Object();
    private ServerConnection serverConnection;

    public SmscSimulator(int port, String expectedSystemId, String expectedPassword) {
        this.port = port;
        this.expectedSystemId = requireText(expectedSystemId, "expectedSystemId");
        this.expectedPassword = requireText(expectedPassword, "expectedPassword");
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverConnection = new ServerSocketConnectionFactory().listen(port);
        journal.append(SmscEventType.LISTENER_STARTED, null, null, null, null, Map.of("port", Integer.toString(port)));
        executor.submit(this::acceptLoop);
    }

    public MessageDispatch send(SmsTestMessage message) {
        BoundSession bound = awaitReceivableSession();
        try {
            return bound.session().dispatch(message, executor).get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("deliver_sm was not written for " + message.testMessageId(), unwrap(exception));
        }
    }

    public java.util.List<SmscEvent> events(String testMessageId) {
        return journal.forMessage(testMessageId);
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        sessions.values().forEach(bound -> bound.session().close());
        sessions.clear();
        try {
            if (serverConnection != null) {
                serverConnection.close();
            }
        } catch (IOException ignored) {
        }
        synchronized (sessionMonitor) {
            sessionMonitor.notifyAll();
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Connection connection = serverConnection.accept();
                ControllableServerSession session = new ControllableServerSession(connection, this::stateChanged, journal);
                executor.submit(() -> negotiateBind(session));
            } catch (IOException exception) {
                if (running.get()) {
                    throw new IllegalStateException("SMPP accept failed", exception);
                }
            }
        }
    }

    private void negotiateBind(ControllableServerSession session) {
        try {
            BindRequest request = session.waitForBind(BIND_TIMEOUT_MS);
            String sessionId = session.getSessionId();
            journal.append(
                SmscEventType.BIND_RECEIVED,
                sessionId,
                null,
                null,
                null,
                Map.of("bindType", request.getBindType().name(), "systemId", request.getSystemId())
            );
            int rejection = rejectionStatus(request);
            if (rejection != SMPPConstant.STAT_ESME_ROK) {
                request.reject(rejection);
                journal.append(SmscEventType.BIND_REJECTED, sessionId, null, null, rejection, Map.of());
                session.close();
                return;
            }
            request.accept("system-proof");
            sessions.put(sessionId, new BoundSession(sessionId, request.getBindType(), session));
            journal.append(SmscEventType.BIND_ACCEPTED, sessionId, null, null, 0, Map.of());
            journal.append(SmscEventType.SESSION_BOUND, sessionId, null, null, null, Map.of());
            synchronized (sessionMonitor) {
                sessionMonitor.notifyAll();
            }
        } catch (Exception exception) {
            session.close();
        }
    }

    private void stateChanged(SessionState newState, SessionState oldState, Session source) {
        if (newState == SessionState.CLOSED) {
            sessions.remove(source.getSessionId());
            journal.append(
                SmscEventType.SESSION_DISCONNECTED,
                source.getSessionId(),
                null,
                null,
                null,
                Map.of("previousState", oldState.name())
            );
        }
    }

    private int rejectionStatus(BindRequest request) {
        if (!expectedSystemId.equals(request.getSystemId())) {
            return SMPPConstant.STAT_ESME_RINVSYSID;
        }
        if (!expectedPassword.equals(request.getPassword())) {
            return SMPPConstant.STAT_ESME_RINVPASWD;
        }
        return SMPPConstant.STAT_ESME_ROK;
    }

    private BoundSession awaitReceivableSession() {
        long deadline = System.nanoTime() + SESSION_WAIT_TIMEOUT.toNanos();
        synchronized (sessionMonitor) {
            while (running.get()) {
                Optional<BoundSession> active = activeReceivableSession();
                if (active.isPresent()) {
                    return active.orElseThrow();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(sessionMonitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for a bound SMPP session", exception);
                }
            }
        }
        throw new IllegalStateException(
            "No bound receiver or transceiver session became available within " + SESSION_WAIT_TIMEOUT
        );
    }

    private Optional<BoundSession> activeReceivableSession() {
        return sessions.values().stream()
            .filter(bound -> bound.session().getSessionState().isBound())
            .filter(bound -> bound.bindType() == BindType.BIND_RX || bound.bindType() == BindType.BIND_TRX)
            .min(Comparator.comparing(BoundSession::sessionId));
    }

    private static Throwable unwrap(Exception exception) {
        return exception.getCause() == null ? exception : exception.getCause();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private record BoundSession(String sessionId, BindType bindType, ControllableServerSession session) {
    }
}
