package io.github.jacekkardys.systemproof.postgresql;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;

/**
 * Stable identity of one explicit transaction on one physical PostgreSQL adapter session.
 *
 * @param sessionOrdinal identity allocated when the adapter opens a physical protocol session
 * @param transactionOrdinal identity allocated for each explicit transaction on that session
 */
public record TransactionRef(long sessionOrdinal, long transactionOrdinal) {
    private static final EvidenceCodec<TransactionRef> CODEC = new Codec();

    public TransactionRef {
        if (sessionOrdinal < 1) {
            throw new IllegalArgumentException("sessionOrdinal must be positive");
        }
        if (transactionOrdinal < 1) {
            throw new IllegalArgumentException("transactionOrdinal must be positive");
        }
    }

    /** Returns the stable versioned codec used by correlation contributions. */
    public static EvidenceCodec<TransactionRef> codec() {
        return CODEC;
    }

    @Override
    public String toString() {
        return "TransactionRef[session=" + sessionOrdinal
            + ", transaction=" + transactionOrdinal + "]";
    }

    private static final class Codec implements EvidenceCodec<TransactionRef> {
        private static final EvidenceSchemaId SCHEMA = new EvidenceSchemaId(
            "system-proof.postgresql",
            "transaction-ref",
            1
        );

        @Override
        public EvidenceSchemaId schemaId() {
            return SCHEMA;
        }

        @Override
        public byte[] encode(TransactionRef reference) {
            if (reference == null) {
                throw new NullPointerException("reference must not be null");
            }
            return ByteBuffer.allocate(Long.BYTES * 2)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(reference.sessionOrdinal)
                .putLong(reference.transactionOrdinal)
                .array();
        }

        @Override
        public TransactionRef decode(byte[] encodedEvidence) {
            if (encodedEvidence == null) {
                throw new NullPointerException("encodedEvidence must not be null");
            }
            if (encodedEvidence.length != Long.BYTES * 2) {
                throw new IllegalArgumentException("Invalid encoded transaction reference");
            }
            ByteBuffer encoded = ByteBuffer.wrap(encodedEvidence).order(ByteOrder.BIG_ENDIAN);
            return new TransactionRef(encoded.getLong(), encoded.getLong());
        }
    }
}
