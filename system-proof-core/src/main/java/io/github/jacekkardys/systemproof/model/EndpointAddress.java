package io.github.jacekkardys.systemproof.model;

import java.util.Objects;

/** One materialized endpoint address. */
public record EndpointAddress(String scheme, String host, int port, String path) {
    public EndpointAddress {
        scheme = requireText(scheme, "scheme");
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535: " + port);
        }
        path = path == null ? "" : path;
        if (!path.isEmpty() && !path.startsWith("/")) {
            throw new IllegalArgumentException("Endpoint path must start with '/': " + path);
        }
    }

    public static EndpointAddress address(String scheme, String host, int port) {
        return new EndpointAddress(scheme, host, port, "");
    }

    public static EndpointAddress address(String scheme, String host, int port, String path) {
        return new EndpointAddress(scheme, host, port, path);
    }

    public String value() {
        return scheme + "://" + host + ":" + port + path;
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
