package io.github.jacekkardys.systemproof.examples.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.examples.sms.environment.SmsExampleEnvironment;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;

/**
 * Baseline smoke test for end-to-end reachability and persistence.
 *
 * <p>This test does not prove the T1 commit-before-positive-acknowledgement invariant.
 */
@SystemProof(environment = SmsExampleEnvironment.class)
@Tag("docker")
@Tag("smoke")
final class SmsIngestionSmokeIT {

    @Test
    void shouldIngestAndPersistOneSms(SmsExampleEnvironment environment) {
        TestSms message = TestSms.unique();
        environment.smsc().send(message);

        SmsPersistence persisted = environment.database().await().rawAndOutboxVisible(message);

        assertThat(persisted.rawCount()).isEqualTo(1);
        assertThat(persisted.outboxCount()).isEqualTo(1);
        assertThat(persisted.rawId()).isEqualTo(persisted.outboxAggregateId());
        assertThat(persisted.externalMessageId()).isNotBlank();
        assertThat(persisted.sourceAddress()).isEqualTo(message.sourceAddress());
        assertThat(persisted.destinationAddress()).isEqualTo(message.destinationAddress());
        assertThat(persisted.content()).isEqualTo(message.content());
    }
}
