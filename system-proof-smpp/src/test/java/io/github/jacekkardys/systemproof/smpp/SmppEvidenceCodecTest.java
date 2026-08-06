package io.github.jacekkardys.systemproof.smpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.Consumer;
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

    @Test
    void shouldRejectImpossiblePublicEvidenceCombinations() {
        assertThatThrownBy(() -> new DeliverSmCompleted(
            EXCHANGE, 172, 156, 121, DataCoding.UCS2, 0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliverSmCompleted(
            EXCHANGE, 172, 156, 122, DataCoding.UCS2, 1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliverSmCompleted(
            EXCHANGE, 172, 155, 122, DataCoding.UCS2, 0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliverSmResponseCompleted(
            EXCHANGE, 0, Acknowledgement.POSITIVE, 18
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionControl(
            Command.ENQUIRE_LINK_RESP, 2, 1, 16
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSemanticallyImpossibleFixedWidthCodecMutations() {
        EvidenceCodec<SmppEvidence> codec = new SmppProtocolAdapter().evidenceCodec();
        byte[] deliver = codec.encode(
            new DeliverSmCompleted(EXCHANGE, 172, 156, 122, DataCoding.UCS2, 0)
        );
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.putInt(38, 1)));
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.put(37, (byte) 2)));
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.putInt(33, 0)));
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.putInt(33, 121)));
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.putInt(33, 158)));
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.putInt(29, 155)));
        assertDecodeRejected(codec, mutate(deliver, buffer -> buffer.putInt(25, 171)));

        byte[] response = codec.encode(new DeliverSmResponseCompleted(
            EXCHANGE, 0, Acknowledgement.POSITIVE, 17
        ));
        assertDecodeRejected(codec, mutate(response, buffer -> buffer.putInt(34, 18)));

        byte[] control = codec.encode(new SessionControl(
            Command.ENQUIRE_LINK_RESP, 2, 0, 16
        ));
        assertDecodeRejected(codec, mutate(control, buffer -> buffer.put(1, (byte) 3)));
        assertDecodeRejected(codec, mutate(control, buffer -> buffer.putLong(10, 1)));
        assertDecodeRejected(codec, mutate(control, buffer -> buffer.putInt(18, 17)));

        byte[] bindRequest = codec.encode(new BindRequested(1, 38));
        assertDecodeRejected(codec, mutate(bindRequest, buffer -> buffer.putInt(9, 47)));
        byte[] bindAccepted = codec.encode(new BindResponded(
            1, 0, BindOutcome.ACCEPTED, 24
        ));
        assertDecodeRejected(codec, mutate(bindAccepted, buffer -> buffer.putInt(18, 16)));
        byte[] bindRejected = codec.encode(new BindResponded(
            1, 14, BindOutcome.REJECTED, 16
        ));
        assertDecodeRejected(codec, mutate(bindRejected, buffer -> buffer.putInt(18, 17)));
    }

    private static byte[] mutate(byte[] encoded, Consumer<ByteBuffer> mutation) {
        byte[] result = encoded.clone();
        mutation.accept(ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN));
        return result;
    }

    private static void assertDecodeRejected(
        EvidenceCodec<SmppEvidence> codec,
        byte[] encoded
    ) {
        assertThatThrownBy(() -> codec.decode(encoded))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
