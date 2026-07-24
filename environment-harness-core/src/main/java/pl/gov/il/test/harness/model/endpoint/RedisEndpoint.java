package pl.gov.il.test.harness.model.endpoint;

import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requirePort;
import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requireText;

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
