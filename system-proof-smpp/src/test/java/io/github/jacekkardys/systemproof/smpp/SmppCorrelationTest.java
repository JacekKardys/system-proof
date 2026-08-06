package io.github.jacekkardys.systemproof.smpp;

import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.boundHarness;
import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.JournalSequence;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.Harness;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

class SmppCorrelationTest {
    private static final long SMPP_SEQUENCE_MAXIMUM = 0x7fff_ffffL;
    private static final long HIGH_BIT_SEQUENCE = 0x8000_0000L;
    private static final long UINT32_SEQUENCE_MAXIMUM = 0xffff_ffffL;
    private static final CorrelationKeySchema KEY_SCHEMA = new CorrelationKeySchema(
        "system-proof.smpp.test",
        "deliver",
        1
    );

    @Test
    void shouldPublishOneDetachedExchangeAndExpireEverySemanticAccessor()
        throws Exception {
        AtomicReference<SmppDeliverInteraction> retained = new AtomicReference<>();
        AtomicReference<SmppDeliverInteraction.Characters> retainedSource =
            new AtomicReference<>();
        AtomicReference<SmppDeliverInteraction.Characters> retainedDestination =
            new AtomicReference<>();
        AtomicReference<SmppDeliverInteraction.Characters> retainedMessage =
            new AtomicReference<>();
        CorrelationKey key = key("one");
        SmppProtocolAdapter adapter = new SmppProtocolAdapter(interaction -> {
            retained.set(interaction);
            retainedSource.set(interaction.sourceAddress());
            retainedDestination.set(interaction.destinationAddress());
            retainedMessage.set(interaction.message());
            assertThat(copy(interaction.sourceAddress())).isEqualTo("111111111111");
            assertThat(copy(interaction.destinationAddress())).isEqualTo("22222");
            assertThat(copy(interaction.message())).isEqualTo("correlated");
            assertThat(interaction.esmClass()).isZero();
            assertThat(interaction.dataCoding()).isEqualTo(8);
            return Optional.of(key);
        });
        Harness harness = boundHarness(adapter);

        ProtocolUnit<SmppEvidence> unit = complete(
            harness.provider(),
            SmppPdus.deliver(88, "correlated")
        );

        DeliverSmCompleted evidence = (DeliverSmCompleted) unit.evidence();
        assertThat(unit.correlationContributions()).containsExactly(
            CorrelationContribution.capture(key, SmppExchangeRef.codec(), evidence.exchange())
        );
        assertThatThrownBy(() -> retained.get().sourceAddress())
            .isInstanceOf(IllegalStateException.class);
        for (SmppDeliverInteraction.Characters characters : List.of(
            retainedSource.get(),
            retainedDestination.get(),
            retainedMessage.get()
        )) {
            assertThatThrownBy(characters::length).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> characters.charAt(0))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> characters.copyTo(0, new char[1], 0, 1))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void shouldPreserveFixtureCompatibleHighBitSequenceAcrossCorrelationAndResponse()
        throws Exception {
        for (long sequence : List.of(
            SMPP_SEQUENCE_MAXIMUM,
            HIGH_BIT_SEQUENCE,
            UINT32_SEQUENCE_MAXIMUM
        )) {
            CorrelationKey key = key(Long.toUnsignedString(sequence));
            SmppProtocolAdapter adapter = new SmppProtocolAdapter(
                interaction -> Optional.of(key)
            );
            Harness harness = boundHarness(adapter);

            ProtocolUnit<SmppEvidence> deliverUnit = complete(
                harness.provider(),
                SmppPdus.deliver(sequence, "high-bit")
            );
            DeliverSmCompleted deliver = (DeliverSmCompleted) deliverUnit.evidence();
            DeliverSmResponseCompleted response = (DeliverSmResponseCompleted) complete(
                harness.consumer(),
                SmppPdus.deliverResponse(sequence, 0)
            ).evidence();

            assertThat(deliver.wireSequenceNumber()).isEqualTo(sequence);
            if (sequence > Integer.MAX_VALUE) {
                assertThat(deliver.wireSequenceNumber()).isGreaterThan(Integer.MAX_VALUE);
            }
            assertThat(deliverUnit.correlationContributions()).containsExactly(
                CorrelationContribution.capture(
                    key,
                    SmppExchangeRef.codec(),
                    deliver.exchange()
                )
            );
            assertThat(response.exchange()).isEqualTo(deliver.exchange());
            assertThat(response.wireSequenceNumber()).isEqualTo(sequence);
            assertThat(response.acknowledgement())
                .isEqualTo(SmppEvidence.Acknowledgement.POSITIVE);
        }
    }

    @Test
    void shouldFailDecodeWhenThePolicyThrowsOrReturnsNull() throws Exception {
        Harness throwing = boundHarness(new SmppProtocolAdapter(interaction -> {
            throw new IllegalStateException("policy-exception-secret");
        }));
        assertThatThrownBy(() -> throwing.provider().decode(ByteBuffer.wrap(
            SmppPdus.deliver(89, "message-secret")
        ))).isInstanceOf(IllegalStateException.class);

        Harness nullPolicy = boundHarness(new SmppProtocolAdapter(interaction -> null));
        assertThatThrownBy(() -> nullPolicy.provider().decode(ByteBuffer.wrap(
            SmppPdus.deliver(90, "message-secret")
        ))).isInstanceOf(NullPointerException.class)
            .hasMessage("SMPP deliver correlation returned null");
    }

    @Test
    void shouldKeepCredentialsAddressesMessageAndTlvsOutOfDurableRepresentations()
        throws Exception {
        String systemIdSecret = "bind-id-secret";
        String passwordSecret = "pwdtoken";
        String sourceSecret = "source-secret";
        String destinationSecret = "destination-secret";
        String messageSecret = "message-secret";
        String optionalSecret = "optional-secret";
        SmppProtocolAdapter adapter = new SmppProtocolAdapter(
            interaction -> Optional.of(key(messageSecret))
        );
        Harness harness = SmppProtocolFramingTest.openHarness(adapter);
        ProtocolUnit<SmppEvidence> bind = complete(
            harness.consumer(),
            SmppPdus.bindRequest(1, systemIdSecret, passwordSecret)
        );
        complete(harness.provider(), SmppPdus.bindResponse(1, 0));
        ProtocolUnit<SmppEvidence> deliver = complete(
            harness.provider(),
            SmppPdus.deliver(
                91,
                sourceSecret,
                destinationSecret,
                messageSecret.getBytes(StandardCharsets.UTF_16BE),
                8,
                0,
                new byte[0]
            )
        );

        byte[] optionalTlv = SmppPdus.tlv(
            0x1400,
            optionalSecret.getBytes(StandardCharsets.US_ASCII)
        );
        Harness unsupported = boundHarness(new SmppProtocolAdapter());
        assertThatThrownBy(() -> unsupported.provider().decode(ByteBuffer.wrap(
            SmppPdus.deliver(
                92,
                "111111111111",
                "22222",
                "safe".getBytes(StandardCharsets.UTF_16BE),
                8,
                0,
                optionalTlv
            )
        ))).hasMessageNotContaining(optionalSecret);

        EvidenceSnapshot bindSnapshot = EvidenceSnapshot.capture(
            adapter.evidenceCodec(),
            bind.evidence()
        );
        EvidenceSnapshot deliverSnapshot = EvidenceSnapshot.capture(
            adapter.evidenceCodec(),
            deliver.evidence()
        );
        InteractionRef reference = new InteractionRef(
            new SessionId(ConnectionId.of("client[].smpp->server[].smpp"), 1),
            FlowDirection.PROVIDER_TO_CONSUMER,
            1
        );
        ScenarioJournalSnapshot journal = new ScenarioJournalSnapshot(List.of(
            new JournalEntry(
                new JournalSequence(1),
                Optional.of(Duration.ZERO),
                new InteractionObservationEvent(reference, bindSnapshot)
            ),
            new JournalEntry(
                new JournalSequence(2),
                Optional.of(Duration.ZERO),
                new InteractionObservationEvent(reference, deliverSnapshot)
            )
        ));
        String rendered = new JournalRenderer().render(journal).content();

        for (String secret : List.of(
            systemIdSecret,
            passwordSecret,
            sourceSecret,
            destinationSecret,
            messageSecret,
            optionalSecret
        )) {
            assertThat(bind.toString()).doesNotContain(secret);
            assertThat(deliver.toString()).doesNotContain(secret);
            assertThat(bind.evidence().toString()).doesNotContain(secret);
            assertThat(deliver.evidence().toString()).doesNotContain(secret);
            assertThat(deliver.correlationContributions().toString()).doesNotContain(secret);
            assertThat(bindSnapshot.toString()).doesNotContain(secret);
            assertThat(deliverSnapshot.toString()).doesNotContain(secret);
            assertThat(journal.entries().toString()).doesNotContain(secret);
            assertThat(rendered).doesNotContain(secret);
            assertThat(new String(
                adapter.evidenceCodec().encode(deliver.evidence()),
                StandardCharsets.ISO_8859_1
            )).doesNotContain(secret);
        }
    }

    private static String copy(SmppDeliverInteraction.Characters characters) {
        char[] copy = new char[characters.length()];
        characters.copyTo(0, copy, 0, copy.length);
        return new String(copy);
    }

    private static CorrelationKey key(String value) {
        try {
            return CorrelationKey.ofDigest(
                KEY_SCHEMA,
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
