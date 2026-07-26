package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Arrays;
import java.util.Objects;

/** One complete decoded interaction and the exact original bytes that formed it. */
public final class ProtocolUnit<E> {
    private final byte[] originalBytes;
    private final E evidence;

    public ProtocolUnit(byte[] originalBytes, E evidence) {
        this.originalBytes = Objects.requireNonNull(
            originalBytes,
            "originalBytes must not be null"
        ).clone();
        if (this.originalBytes.length == 0) {
            throw new IllegalArgumentException("originalBytes must not be empty");
        }
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }

    public byte[] originalBytes() {
        return originalBytes.clone();
    }

    public E evidence() {
        return evidence;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProtocolUnit<?> unit)) {
            return false;
        }
        return Arrays.equals(originalBytes, unit.originalBytes)
            && evidence.equals(unit.evidence);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(originalBytes) + evidence.hashCode();
    }

    @Override
    public String toString() {
        return "ProtocolUnit[originalByteCount=" + originalBytes.length
            + ", evidenceType=" + evidence.getClass().getName() + "]";
    }
}
