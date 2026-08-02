package io.github.jacekkardys.systemproof.examples.sms;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jacekkardys.systemproof.examples.sms.environment.SmsExampleEnvironment;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import org.junit.jupiter.api.Tag;

/**
 * Baseline smoke test for end-to-end reachability and persistence.
 *
 * <p>This test does not prove the T1 commit-before-positive-acknowledgement invariant.
 */
@Tag("docker")
@Tag("smoke")
final class SmsIngestionSmokeIT {

    @SystemProof(
        value = SmsExampleEnvironment.class,
        title = "SMS ingestion smoke test",
        description = "Verifies end-to-end SMS reachability and raw/outbox persistence"
    )
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
