package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.journal.FlowDirection;

/** Test-only four-byte length-prefixed protocol. */
final class LengthPrefixedProtocolAdapter
    implements ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> {

    static final int HEADER_BYTES = Integer.BYTES;
    static final int MALFORMED = -1;
    static final int UNSUPPORTED_ENCRYPTION = -2;
    static final int UNSUPPORTED_NEGOTIATION = -3;
    static final int DESYNCHRONIZED = -4;
    static final int AMBIGUOUS = -5;
    static final EvidenceCodec<FrameEvidence> CODEC = new FrameEvidenceCodec();

    @Override
    public EvidenceCodec<FrameEvidence> evidenceCodec() {
        return CODEC;
    }

    @Override
    public ProtocolSession<FrameEvidence> openSession(ProtocolLimits limits) {
        return direction -> new Decoder(direction, limits);
    }

    static byte[] frame(String payload) {
        return frame(payload.getBytes(UTF_8));
    }

    static byte[] frame(byte[] payload) {
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        frame.putInt(payload.length);
        frame.put(payload);
        return frame.array();
    }

    static byte[] control(int value) {
        return ByteBuffer.allocate(HEADER_BYTES).putInt(value).array();
    }

    record FrameEvidence(
        FlowDirection direction,
        int payloadBytes,
        String payloadSha256
    ) {}

    private static final class Decoder implements ProtocolStream<FrameEvidence> {
        private final FlowDirection direction;
        private final ProtocolLimits limits;

        private Decoder(FlowDirection direction, ProtocolLimits limits) {
            this.direction = direction;
            this.limits = limits;
        }

        @Override
        public ProtocolDecodeResult<FrameEvidence> decode(ByteBuffer bufferedBytes)
            throws ProtocolAdapterException {
            ByteBuffer input = bufferedBytes.asReadOnlyBuffer();
            if (input.remaining() < HEADER_BYTES) {
                return ProtocolDecodeResult.needMoreData();
            }
            int payloadBytes = input.getInt();
            rejectControlValue(payloadBytes);
            long frameBytes = (long) HEADER_BYTES + payloadBytes;
            if (frameBytes > limits.maximumFrameBytes()) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                    "Declared test frame exceeded the configured frame limit"
                );
            }
            if (input.remaining() < payloadBytes) {
                return ProtocolDecodeResult.needMoreData();
            }
            byte[] originalBytes = new byte[Math.toIntExact(frameBytes)];
            ByteBuffer original = bufferedBytes.asReadOnlyBuffer();
            original.get(originalBytes);
            byte[] payload = new byte[payloadBytes];
            ByteBuffer.wrap(originalBytes, HEADER_BYTES, payloadBytes).get(payload);
            return ProtocolDecodeResult.complete(new ProtocolUnit<>(
                originalBytes,
                new FrameEvidence(direction, payloadBytes, sha256(payload))
            ));
        }

        private static void rejectControlValue(int payloadBytes)
            throws ProtocolAdapterException {
            ProtocolFailureKind kind = switch (payloadBytes) {
                case MALFORMED -> ProtocolFailureKind.MALFORMED_INPUT;
                case UNSUPPORTED_ENCRYPTION -> ProtocolFailureKind.UNSUPPORTED_ENCRYPTION;
                case UNSUPPORTED_NEGOTIATION -> ProtocolFailureKind.UNSUPPORTED_NEGOTIATION;
                case DESYNCHRONIZED -> ProtocolFailureKind.DESYNCHRONIZATION;
                case AMBIGUOUS -> ProtocolFailureKind.AMBIGUOUS_FRAMING;
                default -> null;
            };
            if (kind != null) {
                throw new ProtocolAdapterException(kind, "Test protocol control failure");
            }
            if (payloadBytes < 0) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "Negative test payload length"
                );
            }
        }
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static final class FrameEvidenceCodec implements EvidenceCodec<FrameEvidence> {
        private static final EvidenceSchemaId SCHEMA =
            new EvidenceSchemaId("system-proof-test", "length-prefixed-frame", 1);

        @Override
        public EvidenceSchemaId schemaId() {
            return SCHEMA;
        }

        @Override
        public byte[] encode(FrameEvidence evidence) {
            return (
                evidence.direction().name() + "|"
                    + evidence.payloadBytes() + "|"
                    + evidence.payloadSha256()
            ).getBytes(UTF_8);
        }

        @Override
        public FrameEvidence decode(byte[] encodedEvidence) {
            String[] fields = new String(encodedEvidence, UTF_8).split("\\|", -1);
            return new FrameEvidence(
                FlowDirection.valueOf(fields[0]),
                Integer.parseInt(fields[1]),
                fields[2]
            );
        }
    }
}
