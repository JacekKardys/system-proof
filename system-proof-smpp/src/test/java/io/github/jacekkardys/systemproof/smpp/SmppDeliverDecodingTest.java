package io.github.jacekkardys.systemproof.smpp;

import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.assertFailure;
import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.boundHarness;
import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.complete;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DataCoding;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.Harness;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;

class SmppDeliverDecodingTest {

    @Test
    void shouldDecodeOnlyTheCharacterizedUcs2ShortMessageForm() throws Exception {
        Harness harness = boundHarness(new SmppProtocolAdapter());
        byte[] pdu = SmppPdus.deliver(30, "characterized");

        DeliverSmCompleted evidence = (DeliverSmCompleted) complete(
            harness.provider(),
            pdu
        ).evidence();

        assertThat(evidence.dataCoding()).isEqualTo(DataCoding.UCS2);
        assertThat(evidence.esmClass()).isZero();
        assertThat(evidence.messageByteCount())
            .isEqualTo("characterized".getBytes(StandardCharsets.UTF_16BE).length);
        assertThat(evidence.pduByteCount()).isEqualTo(pdu.length);
        assertThat(evidence.bodyByteCount()).isEqualTo(pdu.length - 16);
    }

    @Test
    void shouldRejectMalformedCOctetsAndTlvs() throws Exception {
        byte[] unterminatedServiceType = SmppPdus.deliver(31, "message");
        java.util.Arrays.fill(unterminatedServiceType, 16, 22, (byte) 'x');
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            unterminatedServiceType,
            ProtocolFailureKind.MALFORMED_INPUT
        );

        byte[] malformedTlv = ByteBuffer.allocate(6)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort((short) 0x1400)
            .putShort((short) 8)
            .putShort((short) 1)
            .array();
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                32,
                "111111111111",
                "22222",
                "message".getBytes(StandardCharsets.UTF_16BE),
                8,
                0,
                malformedTlv
            ),
            ProtocolFailureKind.MALFORMED_INPUT
        );
    }

    @Test
    void shouldRejectOptionalParametersAndShortMessagePayloadAmbiguity()
        throws Exception {
        byte[] secretTlv = SmppPdus.tlv(
            0x1400,
            "optional-secret".getBytes(StandardCharsets.US_ASCII)
        );
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                33,
                "111111111111",
                "22222",
                "message".getBytes(StandardCharsets.UTF_16BE),
                8,
                0,
                secretTlv
            ),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );

        byte[] messagePayload = SmppPdus.tlv(
            0x0424,
            "payload-secret".getBytes(StandardCharsets.UTF_16BE)
        );
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                34,
                "111111111111",
                "22222",
                "short".getBytes(StandardCharsets.UTF_16BE),
                8,
                0,
                messagePayload
            ),
            ProtocolFailureKind.AMBIGUOUS_FRAMING
        );
    }

    @Test
    void shouldRejectUnsupportedCodingUdhMultipartAndMalformedUcs2() throws Exception {
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                35,
                "111111111111",
                "22222",
                "ascii".getBytes(StandardCharsets.US_ASCII),
                0,
                0,
                new byte[0]
            ),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                36,
                "111111111111",
                "22222",
                new byte[] {0x05, 0, 3, 1, 2, 1, 0, 65},
                8,
                0x40,
                new byte[0]
            ),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                37,
                "111111111111",
                "22222",
                new byte[] {0},
                8,
                0,
                new byte[0]
            ),
            ProtocolFailureKind.MALFORMED_INPUT
        );
        assertFailure(
            boundHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(
                38,
                "111111111111",
                "22222",
                new byte[] {(byte) 0xd8, 0},
                8,
                0,
                new byte[0]
            ),
            ProtocolFailureKind.MALFORMED_INPUT
        );
    }

    @Test
    void shouldRejectMaximumShortMessagePlusOne() throws Exception {
        SmppProtocolAdapter adapter = new SmppProtocolAdapter(
            new SmppProtocolLimits(4096, 4, 4),
            SmppDeliverCorrelation.none()
        );
        assertFailure(
            boundHarness(adapter).provider(),
            SmppPdus.deliver(
                39,
                "111111111111",
                "22222",
                new byte[] {0, 65, 0, 66, 0, 67},
                8,
                0,
                new byte[0]
            ),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
    }
}
