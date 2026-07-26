package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.journal.EvidenceSnapshot;

/**
 * Immutable adapter-produced request to link one interaction to a safe key and typed native
 * reference.
 *
 * <p>Capture is synchronous: the source reference, codec, and codec-produced byte array are not
 * retained. Rendering exposes only schema identity and encoded size.
 */
public final class CorrelationContribution<T> {
    private final CorrelationKey key;
    private final EvidenceSnapshot nativeReference;

    private CorrelationContribution(
        CorrelationKey key,
        EvidenceSnapshot nativeReference
    ) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.nativeReference = Objects.requireNonNull(
            nativeReference,
            "nativeReference must not be null"
        );
    }

    public static <T> CorrelationContribution<T> capture(
        CorrelationKey key,
        EvidenceCodec<T> codec,
        T nativeReference
    ) {
        return new CorrelationContribution<>(
            key,
            EvidenceSnapshot.capture(codec, nativeReference)
        );
    }

    public CorrelationKey key() {
        return key;
    }

    public EvidenceSchemaId nativeReferenceSchema() {
        return nativeReference.schemaId();
    }

    public int encodedSize() {
        return nativeReference.encodedSize();
    }

    EvidenceSnapshot nativeReferenceSnapshot() {
        return nativeReference;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof CorrelationContribution<?> contribution
                && key.equals(contribution.key)
                && nativeReference.equals(contribution.nativeReference);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + nativeReference.hashCode();
    }

    @Override
    public String toString() {
        return "CorrelationContribution[keySchema=" + key.schema()
            + ", nativeReferenceSchema=" + nativeReference.schemaId()
            + ", encodedBytes=" + nativeReference.encodedSize() + "]";
    }
}
