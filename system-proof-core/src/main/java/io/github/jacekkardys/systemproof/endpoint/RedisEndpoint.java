package io.github.jacekkardys.systemproof.endpoint;

import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requirePort;
import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requireText;

/** Built-in Redis runtime connection value. */
public record RedisEndpoint(String host, int port, int databaseId) {
    public RedisEndpoint {
        host = requireText(host, "Redis host");
        port = requirePort(port, "Redis port");
        if (databaseId < 0) {
            throw new IllegalArgumentException("Redis database ID must not be negative");
        }
    }
}
