package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.engine.ProofSubjectRef;

/** Core-owned fact that one environment execution allocated an opaque proof subject. */
public record ProofSubjectCreatedEvent(
    ProofSubjectRef proofSubject
) implements ScenarioEvent {
    public ProofSubjectCreatedEvent {
        proofSubject = Objects.requireNonNull(
            proofSubject,
            "proofSubject must not be null"
        );
    }
}
