package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.engine.CorrelationCardinality;
import io.github.jacekkardys.systemproof.engine.CorrelationKey;
import io.github.jacekkardys.systemproof.engine.ProofSubjectRef;

/**
 * Core-owned immutable correlation publication and its resulting cardinality.
 *
 * <p>An absent proof subject means that no subject owned the key, or that a shared key prevented
 * selecting one. No arrival-order fallback is represented.
 */
public record CorrelationCandidateEvent(
    Optional<ProofSubjectRef> proofSubject,
    CorrelationKey key,
    InteractionRef interactionRef,
    EvidenceSnapshot nativeReference,
    CorrelationCardinality cardinality
) implements ScenarioEvent {
    public CorrelationCandidateEvent {
        proofSubject = Objects.requireNonNull(
            proofSubject,
            "proofSubject must not be null"
        );
        key = Objects.requireNonNull(key, "key must not be null");
        interactionRef = Objects.requireNonNull(
            interactionRef,
            "interactionRef must not be null"
        );
        nativeReference = Objects.requireNonNull(
            nativeReference,
            "nativeReference must not be null"
        );
        cardinality = Objects.requireNonNull(
            cardinality,
            "cardinality must not be null"
        );
        if (cardinality == CorrelationCardinality.UNIQUE && proofSubject.isEmpty()) {
            throw new IllegalArgumentException(
                "UNIQUE correlation must identify its proof subject"
            );
        }
        if (cardinality == CorrelationCardinality.MISSING && proofSubject.isPresent()) {
            throw new IllegalArgumentException(
                "MISSING correlation must not identify a proof subject"
            );
        }
    }
}
