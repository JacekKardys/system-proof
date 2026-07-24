package pl.gov.il.test.harness.model.endpoint;

import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requirePort;
import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requireText;

import java.util.Objects;
import pl.gov.il.test.harness.model.Secret;

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
