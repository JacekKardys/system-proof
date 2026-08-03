package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic package-private listener used only by gateway lifecycle tests. */
final class ControllableGatewayListener implements GatewayListener {
    private static final long TIMEOUT_SECONDS = 5;

    private final GatewayListener delegate;
    private final int delegatedAccepts;
    private final int scriptedPort;
    private final boolean blockClose;
    private final BlockingQueue<AcceptSignal> accepts = new LinkedBlockingQueue<>();
    private final AtomicInteger acceptCalls = new AtomicInteger();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch closeReleased = new CountDownLatch(1);
    private volatile IOException closeFailure;

    private ControllableGatewayListener(
        GatewayListener delegate,
        int delegatedAccepts,
        int scriptedPort,
        boolean blockClose
    ) {
        this.delegate = delegate;
        this.delegatedAccepts = delegatedAccepts;
        this.scriptedPort = scriptedPort;
        this.blockClose = blockClose;
    }

    static ControllableGatewayListener scripted(int port) {
        return new ControllableGatewayListener(null, 0, port, false);
    }

    static ControllableGatewayListener scriptedWithBlockedClose(int port) {
        return new ControllableGatewayListener(null, 0, port, true);
    }

    static ControllableGatewayListener delegatingFirstAccept() throws IOException {
        return new ControllableGatewayListener(
            ServerSocketGatewayListener.open(),
            1,
            0,
            false
        );
    }

    FailureSignal fail(Throwable failure) {
        FailureSignal signal = new FailureSignal(failure);
        accepts.add(signal);
        return signal;
    }

    void accept(Socket socket) {
        accepts.add(new SocketSignal(socket));
    }

    void failOnClose(IOException failure) {
        closeFailure = Objects.requireNonNull(failure, "failure must not be null");
    }

    void awaitAcceptCalls(int expected) {
        awaitCount(acceptCalls, expected, "accept");
    }

    int acceptCalls() {
        return acceptCalls.get();
    }

    int closeCalls() {
        return closeCalls.get();
    }

    void awaitCloseEntered() {
        await(closeEntered, "Route did not begin listener cleanup");
    }

    void releaseClose() {
        closeReleased.countDown();
    }

    @Override
    public Socket accept() throws IOException {
        int invocation = acceptCalls.incrementAndGet();
        if (delegate != null && invocation <= delegatedAccepts) {
            return delegate.accept();
        }

        AcceptSignal signal;
        try {
            signal = accepts.take();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SocketException failure = new SocketException("Scripted listener was interrupted");
            failure.initCause(interrupted);
            throw failure;
        }
        signal.delivered().countDown();
        if (signal instanceof SocketSignal accepted) {
            return accepted.socket;
        }
        FailureSignal failed = (FailureSignal) signal;
        throwFailure(failed.failure);
        throw new AssertionError("Unreachable listener failure branch");
    }

    @Override
    public int port() {
        return delegate == null ? scriptedPort : delegate.port();
    }

    @Override
    public void close() throws IOException {
        closeCalls.incrementAndGet();
        closeEntered.countDown();
        if (blockClose) {
            await(closeReleased, "Test did not release listener close");
        }

        IOException failure = closeFailure;
        if (delegate != null) {
            try {
                delegate.close();
            } catch (IOException delegateFailure) {
                if (failure == null) {
                    failure = delegateFailure;
                } else {
                    failure.addSuppressed(delegateFailure);
                }
            }
        }
        accepts.offer(new FailureSignal(new SocketException("Listener closed by route")));
        if (failure != null) {
            throw failure;
        }
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException checked) {
            throw checked;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unsupported scripted listener failure", failure);
    }

    private static void awaitCount(
        AtomicInteger actual,
        int expected,
        String operation
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (actual.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (actual.get() < expected) {
            throw new AssertionError(
                "Listener did not enter " + operation + " " + expected + " time(s)"
            );
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private sealed interface AcceptSignal permits SocketSignal, FailureSignal {
        CountDownLatch delivered();
    }

    private static final class SocketSignal implements AcceptSignal {
        private final Socket socket;
        private final CountDownLatch delivered = new CountDownLatch(1);

        private SocketSignal(Socket socket) {
            this.socket = Objects.requireNonNull(socket, "socket must not be null");
        }

        @Override
        public CountDownLatch delivered() {
            return delivered;
        }
    }

    static final class FailureSignal implements AcceptSignal {
        private final Throwable failure;
        private final CountDownLatch delivered = new CountDownLatch(1);

        private FailureSignal(Throwable failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        void awaitDelivery() {
            await(delivered, "Listener did not deliver the scripted failure");
        }

        @Override
        public CountDownLatch delivered() {
            return delivered;
        }
    }
}
