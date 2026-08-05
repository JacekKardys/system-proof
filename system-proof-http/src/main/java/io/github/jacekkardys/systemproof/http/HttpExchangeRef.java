package io.github.jacekkardys.systemproof.http;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;

/**
 * Stable request/response identity within one HTTP adapter instance.
 * Equal ordinals from separate adapters, routes, connections, or gateway sessions are not global
 * exchange identity.
 */
public record HttpExchangeRef(long sessionOrdinal, long requestOrdinal) {
    private static final EvidenceCodec<HttpExchangeRef> CODEC = new Codec();

    public HttpExchangeRef {
        if (sessionOrdinal < 1) {
            throw new IllegalArgumentException("sessionOrdinal must be positive");
        }
        if (requestOrdinal < 1) {
            throw new IllegalArgumentException("requestOrdinal must be positive");
        }
    }

    /** Returns the stable versioned codec used by correlation contributions. */
    public static EvidenceCodec<HttpExchangeRef> codec() {
        return CODEC;
    }

    @Override
    public String toString() {
        return "HttpExchangeRef[session=" + sessionOrdinal
            + ", request=" + requestOrdinal + "]";
    }

    private static final class Codec implements EvidenceCodec<HttpExchangeRef> {
        private static final EvidenceSchemaId SCHEMA = new EvidenceSchemaId(
            "system-proof.http",
            "exchange-ref",
            1
        );

        @Override
        public EvidenceSchemaId schemaId() {
            return SCHEMA;
        }

        @Override
        public byte[] encode(HttpExchangeRef reference) {
            if (reference == null) {
                throw new NullPointerException("reference must not be null");
            }
            return ByteBuffer.allocate(Long.BYTES * 2)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(reference.sessionOrdinal())
                .putLong(reference.requestOrdinal())
                .array();
        }

        @Override
        public HttpExchangeRef decode(byte[] encodedEvidence) {
            if (encodedEvidence == null) {
                throw new NullPointerException("encodedEvidence must not be null");
            }
            if (encodedEvidence.length != Long.BYTES * 2) {
                throw new IllegalArgumentException("Invalid encoded HTTP exchange reference");
            }
            ByteBuffer encoded = ByteBuffer.wrap(encodedEvidence).order(ByteOrder.BIG_ENDIAN);
            return new HttpExchangeRef(encoded.getLong(), encoded.getLong());
        }
    }
}
