package io.github.jacekkardys.systemproof.smpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindOutcome;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindRequested;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindResponded;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Command;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DataCoding;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.SessionControl;

class SmppEvidenceCodecTest {
    private static final SmppExchangeRef EXCHANGE = new SmppExchangeRef(7, 11, 99);

    @Test
    void shouldRoundTripEveryEvidenceVariantAndTheExchangeReference() {
        EvidenceCodec<SmppEvidence> codec = new SmppProtocolAdapter().evidenceCodec();
        List<SmppEvidence> values = List.of(
            new BindRequested(1, 38),
            new BindResponded(1, 0, BindOutcome.ACCEPTED, 24),
            new BindResponded(1, 14, BindOutcome.REJECTED, 16),
            new SessionControl(Command.ENQUIRE_LINK, 2, 0, 16),
            new SessionControl(Command.ENQUIRE_LINK_RESP, 2, 0, 16),
            new SessionControl(Command.UNBIND, 3, 0, 16),
            new SessionControl(Command.UNBIND_RESP, 3, 0, 16),
            new DeliverSmCompleted(EXCHANGE, 172, 156, 122, DataCoding.UCS2, 0),
            new DeliverSmResponseCompleted(
                EXCHANGE,
                0,
                Acknowledgement.POSITIVE,
                17
            ),
            new DeliverSmResponseCompleted(
                EXCHANGE,
                0xffff_ffffL,
                Acknowledgement.NEGATIVE,
                17
            )
        );

        assertThat(codec.schemaId().namespace()).isEqualTo("system-proof.smpp");
        assertThat(codec.schemaId().name()).isEqualTo("wire-evidence");
        assertThat(codec.schemaId().version()).isEqualTo(1);
        assertThat(values).allSatisfy(value ->
            assertThat(codec.decode(codec.encode(value))).isEqualTo(value)
        );

        EvidenceCodec<SmppExchangeRef> referenceCodec = SmppExchangeRef.codec();
        assertThat(referenceCodec.decode(referenceCodec.encode(EXCHANGE))).isEqualTo(EXCHANGE);
        assertThat(referenceCodec.schemaId().version()).isEqualTo(1);
    }

    @Test
    void shouldRejectCorruptedTruncatedAndTrailingEncodings() {
        EvidenceCodec<SmppEvidence> codec = new SmppProtocolAdapter().evidenceCodec();
        byte[] valid = codec.encode(new BindRequested(1, 38));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

        assertThatThrownBy(() -> codec.decode(new byte[] {99, 0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(new byte[] {1, 0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(trailing))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SmppExchangeRef.codec().decode(new byte[23]))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
