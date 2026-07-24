package pl.gov.il.test.harness.testcontainers.component;

/** Known internal container port; the host port is dynamically mapped by Testcontainers. */
public final class PortBinding {
    private final int port;

    private PortBinding(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Container port must be between 1 and 65535: " + port);
        }
        this.port = port;
    }

    public static PortBinding port(int port) {
        return new PortBinding(port);
    }

    public int port() {
        return port;
    }

}
