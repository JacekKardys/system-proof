package io.github.jacekkardys.systemproof.testcontainers.component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Framework-owned TCP/HTTP readiness checks that never inspect container output. */
final class ContainerReadiness {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final int ATTEMPT_TIMEOUT_MILLIS = 250;
    private static final long RETRY_DELAY_MILLIS = 100L;

    private final List<Probe> probes;
    private final Duration timeout;

    ContainerReadiness(List<Probe> probes, Duration timeout) {
        this.probes = List.copyOf(probes);
        this.timeout = validateTimeout(timeout);
    }

    static Duration defaultTimeout() {
        return DEFAULT_TIMEOUT;
    }

    void await(StartedContainer container) {
        Objects.requireNonNull(container, "container must not be null");
        if (probes.isEmpty()) {
            if (!container.isRunning()) {
                throw new ContainerReadinessException();
            }
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!container.isRunning()) {
                throw new ContainerReadinessException();
            }
            if (probes.stream().allMatch(probe -> probe.ready(container))) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ContainerReadinessException();
            }
        }
        throw new ContainerReadinessException();
    }

    private static Duration validateTimeout(Duration timeout) {
        timeout = Objects.requireNonNull(timeout, "readiness timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                "Readiness timeout must be between PT0S and " + MAX_TIMEOUT
            );
        }
        return timeout;
    }

    sealed interface Probe permits TcpProbe, HttpProbe {
        int port();

        boolean ready(StartedContainer container);
    }

    record TcpProbe(int port) implements Probe {
        TcpProbe {
            PortBinding.port(port);
        }

        @Override
        public boolean ready(StartedContainer container) {
            try (Socket socket = new Socket()) {
                socket.connect(
                    new InetSocketAddress(container.host(), container.mappedPort(port)),
                    ATTEMPT_TIMEOUT_MILLIS
                );
                socket.setSoTimeout(ATTEMPT_TIMEOUT_MILLIS);
                return socket.getInputStream().read() >= 0;
            } catch (SocketTimeoutException readyButSilent) {
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    record HttpProbe(int port, String path, int expectedStatus) implements Probe {
        HttpProbe {
            PortBinding.port(port);
            path = Objects.requireNonNull(path, "readiness path must not be null");
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Readiness path must start with '/'");
            }
            if (expectedStatus < 100 || expectedStatus > 599) {
                throw new IllegalArgumentException(
                    "Readiness HTTP status must be between 100 and 599: " + expectedStatus
                );
            }
        }

        @Override
        public boolean ready(StartedContainer container) {
            HttpURLConnection connection = null;
            try {
                URI endpoint = URI.create(
                    "http://" + container.host() + ":" + container.mappedPort(port) + path
                );
                connection = (HttpURLConnection) endpoint.toURL().openConnection();
                connection.setConnectTimeout(ATTEMPT_TIMEOUT_MILLIS);
                connection.setReadTimeout(ATTEMPT_TIMEOUT_MILLIS);
                connection.setInstanceFollowRedirects(false);
                return connection.getResponseCode() == expectedStatus;
            } catch (IOException | IllegalArgumentException ignored) {
                return false;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private static final class ContainerReadinessException extends IllegalStateException {
        private ContainerReadinessException() {
            super("Container readiness check failed");
        }
    }
}
