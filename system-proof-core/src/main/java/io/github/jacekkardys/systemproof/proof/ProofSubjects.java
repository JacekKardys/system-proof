package io.github.jacekkardys.systemproof.proof;

import io.github.jacekkardys.systemproof.observation.EvidenceCodec;

/** Narrow environment-scoped facade for proof-subject allocation, arming, and typed lookup. */
public interface ProofSubjects {
    /** Allocates one opaque subject before proof traffic is emitted. */
    ProofSubjectRef create();

    /** Associates one domain-defined safe key with this environment's subject. */
    void arm(ProofSubjectRef subject, CorrelationKey key);

    /** Returns the explicit typed cardinality for one previously armed subject/key pair. */
    <T> CorrelationResult<T> correlation(
        ProofSubjectRef subject,
        CorrelationKey key,
        EvidenceCodec<T> nativeReferenceCodec
    );
}
