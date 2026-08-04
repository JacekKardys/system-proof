package io.github.jacekkardys.systemproof.observation;

import java.util.Objects;

/** Read-only result of recording one complete typed interaction. */
public record RecordedInteraction(
    InteractionRef interactionRef,
    EvidenceSnapshot evidence
) {
    public RecordedInteraction {
        interactionRef = Objects.requireNonNull(
            interactionRef,
            "interactionRef must not be null"
        );
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }
}
