package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.engine.execution.CorrelationContribution;

/** One complete decoded interaction and the exact original bytes that formed it. */
public final class ProtocolUnit<E> {
    private final byte[] originalBytes;
    private final E evidence;
    private final List<CorrelationContribution<?>> correlationContributions;

    public ProtocolUnit(byte[] originalBytes, E evidence) {
        this(originalBytes, evidence, List.of());
    }

    public ProtocolUnit(
        byte[] originalBytes,
        E evidence,
        List<CorrelationContribution<?>> correlationContributions
    ) {
        this.originalBytes = Objects.requireNonNull(
            originalBytes,
            "originalBytes must not be null"
        ).clone();
        if (this.originalBytes.length == 0) {
            throw new IllegalArgumentException("originalBytes must not be empty");
        }
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(
            correlationContributions,
            "correlationContributions must not be null"
        );
        if (correlationContributions.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(
                "correlationContributions must not contain null"
            );
        }
        this.correlationContributions = List.copyOf(correlationContributions);
    }

    public byte[] originalBytes() {
        return originalBytes.clone();
    }

    public E evidence() {
        return evidence;
    }

    /** Immutable declarative correlation facts for this complete protocol unit. */
    public List<CorrelationContribution<?>> correlationContributions() {
        return correlationContributions;
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
            && evidence.equals(unit.evidence)
            && correlationContributions.equals(unit.correlationContributions);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(originalBytes) + evidence.hashCode())
            + correlationContributions.hashCode();
    }

    @Override
    public String toString() {
        return "ProtocolUnit[originalByteCount=" + originalBytes.length
            + ", evidenceType=" + evidence.getClass().getName()
            + ", correlationContributionCount=" + correlationContributions.size() + "]";
    }
}
