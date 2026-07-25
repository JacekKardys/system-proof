package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ComponentId;

/**
 * Core-owned immutable envelope for one externally contributed typed interaction snapshot.
 */
public record InteractionObservationEvent(
    ComponentId observingComponentId,
    InteractionMetadata metadata,
    EvidenceSnapshot evidence
) implements ScenarioEvent {
    public InteractionObservationEvent {
        observingComponentId = Objects.requireNonNull(
            observingComponentId,
            "observingComponentId must not be null"
        );
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }
}
