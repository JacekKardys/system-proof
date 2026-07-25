package io.github.jacekkardys.systemproof.journal;

import java.util.Arrays;
import java.util.Objects;

/**
 * Framework-owned immutable encoded snapshot of one typed evidence value.
 *
 * <p>The source value, codec, and codec-produced array are never retained. Typed decoding requires
 * a codec with the same schema identity and receives a fresh byte-array copy, so decoded values are
 * caller-owned and cannot mutate journal storage.
 */
public final class EvidenceSnapshot {
    private final EvidenceSchemaId schemaId;
    private final byte[] encodedEvidence;

    private EvidenceSnapshot(EvidenceSchemaId schemaId, byte[] encodedEvidence) {
        this.schemaId = Objects.requireNonNull(schemaId, "schemaId must not be null");
        this.encodedEvidence = Objects.requireNonNull(
            encodedEvidence,
            "encodedEvidence must not be null"
        ).clone();
    }

    /**
     * Captures a detached framework-owned snapshot through one module-owned typed codec.
     */
    public static <T> EvidenceSnapshot capture(EvidenceCodec<T> codec, T evidence) {
        Objects.requireNonNull(codec, "codec must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        EvidenceSchemaId schemaId = Objects.requireNonNull(
            codec.schemaId(),
            "codec schemaId must not be null"
        );
        byte[] encoded = codec.encode(evidence);
        if (encoded == null) {
            throw new NullPointerException(
                "Evidence codec '" + format(schemaId) + "' returned null encoded evidence"
            );
        }
        return new EvidenceSnapshot(schemaId, encoded);
    }

    public EvidenceSchemaId schemaId() {
        return schemaId;
    }

    public int encodedSize() {
        return encodedEvidence.length;
    }

    /**
     * Decodes this snapshot to a new caller-owned typed value.
     *
     * <p>The codec receives a copy and cannot mutate the stored representation.
     */
    public <T> T decode(EvidenceCodec<T> codec) {
        Objects.requireNonNull(codec, "codec must not be null");
        EvidenceSchemaId requested = Objects.requireNonNull(
            codec.schemaId(),
            "codec schemaId must not be null"
        );
        if (!schemaId.equals(requested)) {
            throw new IllegalArgumentException(
                "Evidence schema mismatch: stored='" + format(schemaId)
                    + "', requested='" + format(requested) + "'"
            );
        }
        T decoded = codec.decode(encodedEvidence.clone());
        if (decoded == null) {
            throw new NullPointerException(
                "Evidence codec '" + format(schemaId) + "' returned null decoded evidence"
            );
        }
        return decoded;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EvidenceSnapshot snapshot)) {
            return false;
        }
        return schemaId.equals(snapshot.schemaId)
            && Arrays.equals(encodedEvidence, snapshot.encodedEvidence);
    }

    @Override
    public int hashCode() {
        return 31 * schemaId.hashCode() + Arrays.hashCode(encodedEvidence);
    }

    @Override
    public String toString() {
        return "EvidenceSnapshot[schemaId=" + format(schemaId)
            + ", encodedSize=" + encodedEvidence.length + "]";
    }

    static String format(EvidenceSchemaId schemaId) {
        return schemaId.namespace() + ":" + schemaId.name() + ":v" + schemaId.version();
    }
}
