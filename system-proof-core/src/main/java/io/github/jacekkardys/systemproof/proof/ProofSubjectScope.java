package io.github.jacekkardys.systemproof.proof;

/**
 * Allocates opaque proof-subject identities within one environment execution.
 *
 * <p>The scope owns the identity token and monotonic local sequence. A reference from one scope
 * never belongs to another scope, even when both scopes allocate the same local value.
 */
public final class ProofSubjectScope {
    private final Object owner = new Object();
    private long nextValue = ProofSubjectRef.FIRST_VALUE;

    public synchronized ProofSubjectRef create() {
        if (nextValue < ProofSubjectRef.FIRST_VALUE) {
            throw new IllegalStateException(
                "Proof-subject identity space is exhausted for this environment execution"
            );
        }
        ProofSubjectRef reference = new ProofSubjectRef(owner, nextValue);
        nextValue = nextValue == Long.MAX_VALUE ? Long.MIN_VALUE : nextValue + 1L;
        return reference;
    }

    public boolean owns(ProofSubjectRef reference) {
        return reference != null && reference.belongsTo(owner);
    }
}
