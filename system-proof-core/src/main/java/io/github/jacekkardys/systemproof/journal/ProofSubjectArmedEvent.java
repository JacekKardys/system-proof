package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;

/**
 * Core-owned fact that a proof subject was armed with one safe key.
 *
 * <p>A shared key identifies a duplicate key across subjects. Every association for that key is
 * ambiguous and remains terminal for this environment execution.
 */
public record ProofSubjectArmedEvent(
    ProofSubjectRef proofSubject,
    CorrelationKey key,
    boolean sharedKey
) implements ScenarioEvent {
    public ProofSubjectArmedEvent {
        proofSubject = Objects.requireNonNull(
            proofSubject,
            "proofSubject must not be null"
        );
        key = Objects.requireNonNull(key, "key must not be null");
    }
}
