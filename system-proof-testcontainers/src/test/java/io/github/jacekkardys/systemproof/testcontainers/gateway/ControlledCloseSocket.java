package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Package-private socket seam for deterministic session-cleanup races. */
final class ControlledCloseSocket extends Socket {
    private static final long TIMEOUT_SECONDS = 5;

    private final IOException closeFailure;
    private final boolean blockClose;
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final CountDownLatch setupEntered = new CountDownLatch(1);
    private final CountDownLatch setupReleased = new CountDownLatch(1);
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch closeReleased = new CountDownLatch(1);

    private ControlledCloseSocket(IOException closeFailure, boolean blockClose) {
        this.closeFailure = Objects.requireNonNull(
            closeFailure,
            "closeFailure must not be null"
        );
        this.blockClose = blockClose;
    }

    static ControlledCloseSocket failingWith(IOException closeFailure) {
        return new ControlledCloseSocket(closeFailure, false);
    }

    static ControlledCloseSocket blockedClose(IOException closeFailure) {
        return new ControlledCloseSocket(closeFailure, true);
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        setupEntered.countDown();
        await(setupReleased, "Test did not release socket setup");
        throw new SocketException("Controlled session setup failure");
    }

    @Override
    public synchronized void close() throws IOException {
        closeCalls.incrementAndGet();
        closeEntered.countDown();
        if (blockClose) {
            awaitUninterruptibly(closeReleased, "Test did not release socket close");
        }
        throw closeFailure;
    }

    void awaitSetupEntered() {
        await(setupEntered, "Session did not begin socket setup");
    }

    void releaseSetup() {
        setupReleased.countDown();
    }

    void awaitCloseEntered() {
        await(closeEntered, "Session did not begin socket cleanup");
    }

    void releaseClose() {
        closeReleased.countDown();
    }

    int closeCalls() {
        return closeCalls.get();
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

    private static void awaitUninterruptibly(CountDownLatch latch, String message) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try {
            while (latch.getCount() != 0 && System.nanoTime() < deadline) {
                try {
                    latch.await(
                        Math.max(1, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS
                    );
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (latch.getCount() != 0) {
                throw new AssertionError(message);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
