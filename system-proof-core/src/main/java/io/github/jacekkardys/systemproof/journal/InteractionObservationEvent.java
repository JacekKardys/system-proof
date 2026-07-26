package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
/**
 * Core-owned immutable envelope for one connection-scoped typed interaction snapshot.
 */
public record InteractionObservationEvent(
    InteractionRef interactionRef,
    EvidenceSnapshot evidence
) implements ScenarioEvent {
    public InteractionObservationEvent {
        interactionRef = Objects.requireNonNull(
            interactionRef,
            "interactionRef must not be null"
        );
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }
}
