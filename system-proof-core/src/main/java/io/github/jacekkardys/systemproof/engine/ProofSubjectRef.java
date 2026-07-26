package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;

/**
 * Opaque identity of one scenario-selected operation in one environment execution.
 *
 * <p>Only the environment runtime can allocate references. The ownership token and numeric
 * identity are intentionally not exposed.
 */
public final class ProofSubjectRef {
    static final long FIRST_VALUE = 1L;

    private final Object environmentOwner;
    private final long value;

    ProofSubjectRef(Object environmentOwner, long value) {
        this.environmentOwner = Objects.requireNonNull(
            environmentOwner,
            "environmentOwner must not be null"
        );
        if (value < FIRST_VALUE) {
            throw new IllegalArgumentException(
                "proof-subject value must be at least " + FIRST_VALUE
            );
        }
        this.value = value;
    }

    boolean belongsTo(Object owner) {
        return environmentOwner == owner;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof ProofSubjectRef reference
                && environmentOwner == reference.environmentOwner
                && value == reference.value;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(environmentOwner) + Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "proof-subject-" + value;
    }
}
