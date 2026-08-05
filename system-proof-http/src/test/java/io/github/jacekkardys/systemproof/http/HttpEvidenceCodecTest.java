package io.github.jacekkardys.systemproof.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.http.HttpEvidence.ResponseCompleted;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;

class HttpEvidenceCodecTest {
    private static final HttpExchangeRef EXCHANGE = new HttpExchangeRef(7, 11);

    @Test
    void shouldRoundTripEveryEvidenceVariantThroughVersionOneSchemas() {
        EvidenceCodec<HttpEvidence> evidenceCodec = new HttpProtocolAdapter().evidenceCodec();
        List<HttpEvidence> values = List.of(
            new RequestCompleted(
                EXCHANGE,
                "POST",
                "/v1/ingestion/sms",
                Optional.of("application/x-www-form-urlencoded"),
                123
            ),
            new RequestCompleted(EXCHANGE, "GET", "/health", Optional.empty(), 0),
            new ResponseCompleted(EXCHANGE, 200, Acknowledgement.POSITIVE, 10),
            new ResponseCompleted(EXCHANGE, 500, Acknowledgement.NEGATIVE, 0)
        );

        assertThat(evidenceCodec.schemaId().namespace()).isEqualTo("system-proof.http");
        assertThat(evidenceCodec.schemaId().name()).isEqualTo("wire-evidence");
        assertThat(evidenceCodec.schemaId().version()).isEqualTo(1);
        assertThat(values).allSatisfy(value ->
            assertThat(evidenceCodec.decode(evidenceCodec.encode(value))).isEqualTo(value)
        );

        EvidenceCodec<HttpExchangeRef> referenceCodec = HttpExchangeRef.codec();
        assertThat(referenceCodec.schemaId().version()).isEqualTo(1);
        assertThat(referenceCodec.decode(referenceCodec.encode(EXCHANGE))).isEqualTo(EXCHANGE);
    }

    @Test
    void shouldRejectMalformedEvidenceAndReferenceEncodings() {
        EvidenceCodec<HttpEvidence> evidenceCodec = new HttpProtocolAdapter().evidenceCodec();

        assertThatThrownBy(() -> evidenceCodec.decode(new byte[] {99, 0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evidenceCodec.decode(new byte[] {1, 0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpExchangeRef.codec().decode(new byte[3]))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
