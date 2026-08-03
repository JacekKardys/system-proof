package io.github.jacekkardys.systemproof.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.configuration.Secret;

class BuiltInEndpointTest {

    @Test
    void shouldCreateBuiltInEndpoints() {
        Secret<String> password = Secret.secret("secret");

        assertThat(new JdbcEndpoint("jdbc:postgresql://db:5432/app", "app", password).url())
            .isEqualTo("jdbc:postgresql://db:5432/app");
        assertThat(new SmppEndpoint("smsc", 2775, "system", password).port())
            .isEqualTo(2775);
        assertThat(new AmqpEndpoint("rabbitmq", 5672, "/", "app", password).virtualHost())
            .isEqualTo("/");
        assertThat(new RedisEndpoint("redis", 6379, 0).databaseId())
            .isZero();
    }

    @Test
    void shouldRejectInvalidBuiltInEndpointValues() {
        Secret<String> password = Secret.secret("secret");

        assertThatThrownBy(() -> new JdbcEndpoint(" ", "app", password))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("JDBC URL must not be blank");
        assertThatThrownBy(() -> new SmppEndpoint("smsc", 0, "system", password))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("SMPP port must be between 1 and 65535");
        assertThatThrownBy(() -> new AmqpEndpoint("rabbitmq", 5672, "", "app", password))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("AMQP virtual host must not be blank");
        assertThatThrownBy(() -> new RedisEndpoint("redis", 6379, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Redis database ID must not be negative");
    }
}
