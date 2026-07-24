package io.github.jacekkardys.systemproof.examples.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.examples.sms.environment.SmsExampleEnvironment;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;
import io.github.jacekkardys.systemproof.junit.EnvironmentTest;

/** Verifies that one SMS crosses the real container topology and is persisted. */
@EnvironmentTest(environment = SmsExampleEnvironment.class)
@Tag("docker")
@Tag("smoke")
final class SmsIngestionSmokeIT {

    @Test
    void shouldIngestAndPersistOneSms(SmsExampleEnvironment environment) {
        TestSms message = TestSms.unique();
        environment.smsc().send(message);

        SmsPersistence persisted = environment.database().await().rawAndOutboxVisible(message);
        var response = environment.smsc().await().responseReceived(message);

        assertThat(response.deliverSmCount()).isEqualTo(1);
        assertThat(response.responseCount()).isEqualTo(1);
        assertThat(response.deliveredSequenceNumber()).isEqualTo(101);
        assertThat(response.sequenceNumber()).isEqualTo(101);
        assertThat(response.deliveredSessionId()).isNotBlank();
        assertThat(response.sessionId()).isEqualTo(response.deliveredSessionId());
        assertThat(response.commandStatus()).isZero();
        assertThat(response.eventIndex()).isGreaterThan(response.deliveredEventIndex());
        assertThat(persisted.rawCount()).isEqualTo(1);
        assertThat(persisted.outboxCount()).isEqualTo(1);
        assertThat(persisted.rawId()).isEqualTo(persisted.outboxAggregateId());
        assertThat(persisted.externalMessageId()).isNotBlank();
        assertThat(persisted.sourceAddress()).isEqualTo(message.sourceAddress());
        assertThat(persisted.destinationAddress()).isEqualTo(message.destinationAddress());
        assertThat(persisted.content()).contains(message.content());
    }
}
