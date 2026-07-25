package io.github.jacekkardys.systemproof.journal;

/**
 * Module-owned typed codec for one evidence schema.
 *
 * <p>The codec is invoked synchronously at the contribution boundary and is never retained in the
 * journal. The framework copies encoded bytes before storage and supplies a fresh byte array for
 * every decode. Implementations should reject unsupported source values with an actionable
 * {@link IllegalArgumentException}.
 *
 * @param <T> module-owned typed evidence value
 */
public interface EvidenceCodec<T> {
    EvidenceSchemaId schemaId();

    byte[] encode(T evidence);

    T decode(byte[] encodedEvidence);
}
