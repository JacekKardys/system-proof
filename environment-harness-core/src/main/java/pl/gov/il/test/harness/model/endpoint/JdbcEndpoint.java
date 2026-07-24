package pl.gov.il.test.harness.model.endpoint;

import static pl.gov.il.test.harness.model.endpoint.EndpointValues.requireText;

import java.util.Objects;
import pl.gov.il.test.harness.model.Secret;

/** Built-in JDBC runtime connection value. */
public record JdbcEndpoint(String url, String username, Secret<String> password) {
    public JdbcEndpoint {
        url = requireText(url, "JDBC URL");
        username = requireText(username, "JDBC username");
        password = Objects.requireNonNull(password, "JDBC password must not be null");
    }
}
