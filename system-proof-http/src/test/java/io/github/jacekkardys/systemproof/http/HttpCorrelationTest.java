package io.github.jacekkardys.systemproof.http;

import static io.github.jacekkardys.systemproof.http.HttpProtocolFramingTest.complete;
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
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.JournalSequence;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

class HttpCorrelationTest {
    private static final ProtocolLimits LIMITS = new ProtocolLimits(4096, 8192);
    private static final CorrelationKeySchema KEY_SCHEMA = new CorrelationKeySchema(
        "system-proof.http.test",
        "request",
        1
    );

    @Test
    void shouldPublishOneDetachedExchangeContributionFromTheEphemeralRequest()
        throws Exception {
        AtomicReference<HttpRequestInteraction> retained = new AtomicReference<>();
        AtomicReference<HttpRequestInteraction.Body> retainedBody = new AtomicReference<>();
        CorrelationKey key = key("one");
        HttpProtocolAdapter adapter = new HttpProtocolAdapter(interaction -> {
            retained.set(interaction);
            assertThat(interaction.method()).isEqualTo("POST");
            assertThat(interaction.path()).isEqualTo("/v1/ingestion/sms");
            assertThat(interaction.contentType())
                .contains("application/x-www-form-urlencoded");
            retainedBody.set(interaction.body());
            byte[] body = new byte[interaction.body().size()];
            interaction.body().copyTo(0, body, 0, body.length);
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("id=one");
            assertThat(interaction.body().byteAt(0)).isEqualTo((byte) 'i');
            return Optional.of(key);
        });

        ProtocolUnit<HttpEvidence> unit = complete(requests(adapter), HttpMessages.request("id=one"));

        assertThat(unit.correlationContributions()).hasSize(1);
        CorrelationContribution<?> contribution = unit.correlationContributions().getFirst();
        assertThat(contribution.key()).isEqualTo(key);
        assertThat(contribution).isEqualTo(CorrelationContribution.capture(
            key,
            HttpExchangeRef.codec(),
            ((RequestCompleted) unit.evidence()).exchange()
        ));
        assertThatThrownBy(() -> retained.get().body())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retainedBody.get().size())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retainedBody.get().byteAt(0))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retainedBody.get().copyTo(0, new byte[1], 0, 1))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailTheDecodeWhenThePolicyThrowsOrReturnsNull() {
        assertThatThrownBy(() -> requests(new HttpProtocolAdapter(interaction -> {
            throw new IllegalStateException("policy failed with secret-value");
        })).decode(ByteBuffer.wrap(HttpMessages.request("secret-value"))))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> requests(new HttpProtocolAdapter(interaction -> null))
            .decode(ByteBuffer.wrap(HttpMessages.request("secret-value"))))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("HTTP request correlation returned null");
    }

    @Test
    void shouldKeepBodiesAndCorrelationMaterialOutOfDefaultRepresentations()
        throws Exception {
        String secret = "secret-message-token";
        ProtocolUnit<HttpEvidence> unit = complete(
            requests(new HttpProtocolAdapter(interaction -> Optional.of(key(secret)))),
            HttpMessages.request(secret)
        );

        assertThat(unit.toString()).doesNotContain(secret);
        assertThat(unit.evidence().toString()).doesNotContain(secret);
        assertThat(unit.correlationContributions().toString()).doesNotContain(secret);
    }

    @Test
    void shouldKeepPathBodyAndFormSecretsOutOfEvidenceAndItsEncoding() throws Exception {
        String secret = "secret-evidence-token";
        HttpProtocolAdapter adapter = new HttpProtocolAdapter(interaction -> Optional.empty());
        ProtocolUnit<HttpEvidence> unit = complete(
            requests(adapter),
            HttpMessages.request("POST", "/callback/" + secret, "token=" + secret)
        );
        byte[] encoded = adapter.evidenceCodec().encode(unit.evidence());

        assertThat(unit.evidence().toString()).doesNotContain(secret);
        assertThat(unit.toString()).doesNotContain(secret);
        assertThat(new String(encoded, StandardCharsets.ISO_8859_1)).doesNotContain(secret);

        EvidenceSnapshot snapshot = EvidenceSnapshot.capture(
            adapter.evidenceCodec(),
            unit.evidence()
        );
        InteractionRef reference = new InteractionRef(
            new SessionId(ConnectionId.of("client[].api->server[].api"), 1),
            FlowDirection.CONSUMER_TO_PROVIDER,
            1
        );
        ScenarioJournalSnapshot journal = new ScenarioJournalSnapshot(List.of(
            new JournalEntry(
                new JournalSequence(1),
                Optional.of(Duration.ZERO),
                new InteractionObservationEvent(reference, snapshot)
            )
        ));

        assertThat(snapshot.toString()).doesNotContain(secret);
        assertThat(journal.entries().toString()).doesNotContain(secret);
        assertThat(new JournalRenderer().render(journal).content()).doesNotContain(secret);
    }

    private static ProtocolStream<HttpEvidence> requests(HttpProtocolAdapter adapter) {
        return adapter.openSession(LIMITS).openStream(FlowDirection.CONSUMER_TO_PROVIDER);
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
