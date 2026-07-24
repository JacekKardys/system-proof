package io.github.jacekkardys.systemproof.model.communication;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.model.Communication;

class BuiltInCommunicationTest {

    @Test
    void shouldDeclareBuiltInCommunicationSemantics() {
        assertCommunication(Communication.Http.class, "invocation", "http", "");
        assertCommunication(
            Communication.JdbcPostgresql.class,
            "resource-access",
            "jdbc-postgresql",
            "jdbc:postgresql"
        );
        assertCommunication(Communication.Smpp.class, "session", "smpp", "");
        assertCommunication(Communication.Amqp.class, "messaging", "amqp", "");
        assertCommunication(Communication.Redis.class, "resource-access", "redis", "");
        assertCommunication(Communication.Tcp.class, "session", "tcp", "");
    }

    private static void assertCommunication(
        Class<? extends Annotation> annotationType,
        String interaction,
        String protocol,
        String scheme
    ) {
        Communication communication = annotationType.getAnnotation(Communication.class);

        assertThat(communication).isNotNull();
        assertThat(communication.interaction()).isEqualTo(interaction);
        assertThat(communication.protocol()).isEqualTo(protocol);
        assertThat(communication.scheme()).isEqualTo(scheme);
    }
}
