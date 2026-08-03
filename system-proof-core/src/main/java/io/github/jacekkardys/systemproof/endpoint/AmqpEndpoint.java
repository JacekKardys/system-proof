package io.github.jacekkardys.systemproof.endpoint;

import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requirePort;
import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requireText;

import java.util.Objects;
import io.github.jacekkardys.systemproof.configuration.Secret;

/** Built-in AMQP runtime connection value. */
public record AmqpEndpoint(
    String host,
    int port,
    String virtualHost,
    String username,
    Secret<String> password
) {
    public AmqpEndpoint {
        host = requireText(host, "AMQP host");
        port = requirePort(port, "AMQP port");
        virtualHost = requireText(virtualHost, "AMQP virtual host");
        username = requireText(username, "AMQP username");
        password = Objects.requireNonNull(password, "AMQP password must not be null");
    }
}
