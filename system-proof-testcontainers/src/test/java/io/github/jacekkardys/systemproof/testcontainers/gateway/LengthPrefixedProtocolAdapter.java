package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Feature;

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
    static final EvidenceCodec<FrameNativeReference> NATIVE_REFERENCE_CODEC =
        new FrameNativeReferenceCodec();
    static final CorrelationKeySchema CORRELATION_KEY_SCHEMA =
        new CorrelationKeySchema(
            "system-proof-test",
            "length-prefixed-payload",
            1
        );

    private final boolean publishesCorrelations;
    private final List<CorrelationKey> fixedCorrelationKeys;

    LengthPrefixedProtocolAdapter() {
        this(false, List.of());
    }

    private LengthPrefixedProtocolAdapter(
        boolean publishesCorrelations,
        List<CorrelationKey> fixedCorrelationKeys
    ) {
        this.publishesCorrelations = publishesCorrelations;
        this.fixedCorrelationKeys = List.copyOf(fixedCorrelationKeys);
    }

    static LengthPrefixedProtocolAdapter correlating() {
        return new LengthPrefixedProtocolAdapter(true, List.of());
    }

    static LengthPrefixedProtocolAdapter correlating(
        CorrelationKey first,
        CorrelationKey... additional
    ) {
        List<CorrelationKey> keys = new ArrayList<>();
        keys.add(Objects.requireNonNull(first, "first must not be null"));
        keys.addAll(List.of(additional));
        return new LengthPrefixedProtocolAdapter(true, keys);
    }

    @Override
    public Optional<ProtocolObservationContract> observationContract() {
        return Optional.of(new ProtocolObservationContract(
            "http",
            "http",
            InteractionGatewayTest.CommandEndpoint.class,
            CODEC.schemaId(),
            Optional.of(NATIVE_REFERENCE_CODEC.schemaId()),
            Set.of(
                Capability.CORRELATION_CONTRIBUTIONS,
                Capability.SEMANTIC_CONTROL
            ),
            Set.of(),
            Set.of(
                Feature.ENCRYPTED_TRANSPORT,
                Feature.GENERAL_PIPELINING
            )
        ));
    }

    @Override
    public EvidenceCodec<FrameEvidence> evidenceCodec() {
        return CODEC;
    }

    @Override
    public ProtocolSession<FrameEvidence> openSession(ProtocolLimits limits) {
        return direction -> new Decoder(
            direction,
            limits,
            publishesCorrelations,
            fixedCorrelationKeys
        );
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

    static CorrelationKey correlationKey(String normalizedPayload) {
        return correlationKey(normalizedPayload.getBytes(UTF_8));
    }

    static CorrelationKey correlationKey(byte[] normalizedPayload) {
        return CorrelationKey.ofDigest(
            CORRELATION_KEY_SCHEMA,
            sha256Digest(normalizedPayload)
        );
    }

    record FrameEvidence(
        FlowDirection direction,
        int payloadBytes,
        String payloadSha256
    ) {}

    record FrameNativeReference(
        FlowDirection direction,
        int payloadBytes,
        String payloadSha256
    ) {}

    private static final class Decoder implements ProtocolStream<FrameEvidence> {
        private final FlowDirection direction;
        private final ProtocolLimits limits;
        private final boolean publishesCorrelations;
        private final List<CorrelationKey> fixedCorrelationKeys;

        private Decoder(
            FlowDirection direction,
            ProtocolLimits limits,
            boolean publishesCorrelations,
            List<CorrelationKey> fixedCorrelationKeys
        ) {
            this.direction = direction;
            this.limits = limits;
            this.publishesCorrelations = publishesCorrelations;
            this.fixedCorrelationKeys = fixedCorrelationKeys;
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
            String payloadSha256 = sha256(payload);
            FrameEvidence evidence =
                new FrameEvidence(direction, payloadBytes, payloadSha256);
            if (!publishesCorrelations) {
                return ProtocolDecodeResult.complete(new ProtocolUnit<>(
                    originalBytes,
                    evidence
                ));
            }
            FrameNativeReference nativeReference = new FrameNativeReference(
                direction,
                payloadBytes,
                payloadSha256
            );
            List<CorrelationKey> keys = fixedCorrelationKeys.isEmpty()
                ? List.of(correlationKey(payload))
                : fixedCorrelationKeys;
            List<CorrelationContribution<?>> correlations = keys.stream()
                .<CorrelationContribution<?>>map(key -> CorrelationContribution.capture(
                    key,
                    NATIVE_REFERENCE_CODEC,
                    nativeReference
                ))
                .toList();
            return ProtocolDecodeResult.complete(new ProtocolUnit<>(
                originalBytes,
                evidence,
                correlations
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
        return HexFormat.of().formatHex(sha256Digest(value));
    }

    private static byte[] sha256Digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
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

    private static final class FrameNativeReferenceCodec
        implements EvidenceCodec<FrameNativeReference> {

        private static final EvidenceSchemaId SCHEMA =
            new EvidenceSchemaId(
                "system-proof-test",
                "length-prefixed-native-reference",
                1
            );

        @Override
        public EvidenceSchemaId schemaId() {
            return SCHEMA;
        }

        @Override
        public byte[] encode(FrameNativeReference reference) {
            return (
                reference.direction().name() + "|"
                    + reference.payloadBytes() + "|"
                    + reference.payloadSha256()
            ).getBytes(UTF_8);
        }

        @Override
        public FrameNativeReference decode(byte[] encodedReference) {
            String[] fields = new String(encodedReference, UTF_8).split("\\|", -1);
            return new FrameNativeReference(
                FlowDirection.valueOf(fields[0]),
                Integer.parseInt(fields[1]),
                fields[2]
            );
        }
    }
}
