package io.github.jacekkardys.systemproof.endpoint;

import static io.github.jacekkardys.systemproof.endpoint.EndpointValues.requireText;

import java.util.Objects;
import io.github.jacekkardys.systemproof.configuration.Secret;

/** Built-in JDBC runtime connection value. */
public record JdbcEndpoint(String url, String username, Secret<String> password) {
    public JdbcEndpoint {
        url = requireText(url, "JDBC URL");
        username = requireText(username, "JDBC username");
        password = Objects.requireNonNull(password, "JDBC password must not be null");
    }
}
