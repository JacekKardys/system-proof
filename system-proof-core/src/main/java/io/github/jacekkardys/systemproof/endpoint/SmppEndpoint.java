package io.github.jacekkardys.systemproof.endpoint;

import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requirePort;
import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requireText;

import java.util.Objects;
import io.github.jacekkardys.systemproof.configuration.Secret;

/** Built-in SMPP runtime connection value. */
public record SmppEndpoint(
    String host,
    int port,
    String systemId,
    Secret<String> password
) {
    public SmppEndpoint {
        host = requireText(host, "SMPP host");
        port = requirePort(port, "SMPP port");
        systemId = requireText(systemId, "SMPP system ID");
        password = Objects.requireNonNull(password, "SMPP password must not be null");
    }
}
