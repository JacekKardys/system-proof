package pl.gov.il.test.harness.model.endpoint;

import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requirePort;
import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requireText;

import java.util.Objects;
import pl.gov.il.test.harness.model.Secret;

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
