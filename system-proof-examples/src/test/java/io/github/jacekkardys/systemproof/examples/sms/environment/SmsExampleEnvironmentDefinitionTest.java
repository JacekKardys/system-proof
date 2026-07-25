package io.github.jacekkardys.systemproof.examples.sms.environment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.PortRef;

class SmsExampleEnvironmentDefinitionTest {

    @Test
    void shouldPreserveComponentPortAndConnectionIdentities() {
        try (SmsExampleEnvironment environment = SmsExampleEnvironment.define()) {
            assertThat(environment.components())
                .extracting(component -> component.id().toString())
                .containsExactly(
                    "system-proof-smsc-simulator",
                    "jasmin",
                    "ingestion",
                    "postgres",
                    "rabbitmq",
                    "redis"
                );
            assertThat(environment.components())
                .flatExtracting(Component::ports)
                .extracting(SmsExampleEnvironmentDefinitionTest::portIdentity)
                .containsExactly(
                    "system-proof-smsc-simulator.smpp=smpp",
                    "system-proof-smsc-simulator.control=control",
                    "jasmin.smpp=smpp",
                    "jasmin.sms=sms",
                    "jasmin.amqp=amqp",
                    "jasmin.redis=redis",
                    "jasmin.administration=administration",
                    "ingestion.sms=sms",
                    "ingestion.jdbc=jdbc",
                    "postgres.jdbc=jdbc",
                    "rabbitmq.amqp=amqp",
                    "redis.redis=redis"
                );
            assertThat(environment.connections())
                .extracting(connection -> connection.id().toString())
                .containsExactly(
                    "jasmin[].smpp->system-proof-smsc-simulator[].smpp",
                    "jasmin[].sms->ingestion[].sms",
                    "ingestion[].jdbc->postgres[].jdbc",
                    "jasmin[].amqp->rabbitmq[].amqp",
                    "jasmin[].redis->redis[].redis"
                );
        }
    }

    private static String portIdentity(PortRef port) {
        return port.owner().id() + "." + port.name() + "=" + port.contractId();
    }
}
