package io.github.jacekkardys.systemproof.smpp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;

/** Stable local identity for one deliver_sm exchange within one adapter instance. */
public record SmppExchangeRef(
    long adapterSessionOrdinal,
    long exchangeOrdinal,
    long wireSequenceNumber
) {
    private static final EvidenceCodec<SmppExchangeRef> CODEC = new Codec();

    public SmppExchangeRef {
        if (adapterSessionOrdinal < 1) {
            throw new IllegalArgumentException("adapterSessionOrdinal must be positive");
        }
        if (exchangeOrdinal < 1) {
            throw new IllegalArgumentException("exchangeOrdinal must be positive");
        }
        if (wireSequenceNumber < 1 || wireSequenceNumber > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                "wireSequenceNumber must be an unsigned non-zero 32-bit value"
            );
        }
    }

    /** Returns the versioned codec used by correlation contributions. */
    public static EvidenceCodec<SmppExchangeRef> codec() {
        return CODEC;
    }

    @Override
    public String toString() {
        return "SmppExchangeRef[session=" + adapterSessionOrdinal
            + ", exchange=" + exchangeOrdinal
            + ", wireSequence=" + wireSequenceNumber + "]";
    }

    private static final class Codec implements EvidenceCodec<SmppExchangeRef> {
        private static final EvidenceSchemaId SCHEMA = new EvidenceSchemaId(
            "system-proof.smpp",
            "exchange-ref",
            1
        );

        @Override
        public EvidenceSchemaId schemaId() {
            return SCHEMA;
        }

        @Override
        public byte[] encode(SmppExchangeRef reference) {
            if (reference == null) {
                throw new NullPointerException("reference must not be null");
            }
            return ByteBuffer.allocate(Long.BYTES * 3)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(reference.adapterSessionOrdinal())
                .putLong(reference.exchangeOrdinal())
                .putLong(reference.wireSequenceNumber())
                .array();
        }

        @Override
        public SmppExchangeRef decode(byte[] encodedEvidence) {
            if (encodedEvidence == null) {
                throw new NullPointerException("encodedEvidence must not be null");
            }
            if (encodedEvidence.length != Long.BYTES * 3) {
                throw new IllegalArgumentException("Invalid encoded SMPP exchange reference");
            }
            ByteBuffer encoded = ByteBuffer.wrap(encodedEvidence).order(ByteOrder.BIG_ENDIAN);
            return new SmppExchangeRef(
                encoded.getLong(),
                encoded.getLong(),
                encoded.getLong()
            );
        }
    }
}
