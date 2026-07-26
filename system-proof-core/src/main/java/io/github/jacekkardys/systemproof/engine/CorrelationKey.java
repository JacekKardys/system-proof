package io.github.jacekkardys.systemproof.engine;

import java.util.Arrays;
import java.util.Objects;

/**
 * Protocol-neutral correlation identity containing only domain-produced digest material.
 *
 * <p>Domains and adapters normalize source values and calculate a digest before calling
 * {@link #ofDigest(CorrelationKeySchema, byte[])}. Core never receives the normalized source
 * value. The digest is copied on input and is never exposed or rendered.
 */
public final class CorrelationKey {
    private static final int MINIMUM_DIGEST_BYTES = 16;
    private static final int MAXIMUM_DIGEST_BYTES = 64;

    private final CorrelationKeySchema schema;
    private final byte[] digest;

    private CorrelationKey(CorrelationKeySchema schema, byte[] digest) {
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
        if (digest.length < MINIMUM_DIGEST_BYTES
            || digest.length > MAXIMUM_DIGEST_BYTES) {
            throw new IllegalArgumentException(
                "correlation digest must contain between " + MINIMUM_DIGEST_BYTES
                    + " and " + MAXIMUM_DIGEST_BYTES + " bytes"
            );
        }
        this.digest = digest.clone();
    }

    public static CorrelationKey ofDigest(
        CorrelationKeySchema schema,
        byte[] digest
    ) {
        return new CorrelationKey(schema, digest);
    }

    public CorrelationKeySchema schema() {
        return schema;
    }

    public int digestSize() {
        return digest.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CorrelationKey key)) {
            return false;
        }
        return schema.equals(key.schema) && Arrays.equals(digest, key.digest);
    }

    @Override
    public int hashCode() {
        return 31 * schema.hashCode() + Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
        return "CorrelationKey[schema=" + schema + ", digestBytes=" + digest.length + "]";
    }
}
