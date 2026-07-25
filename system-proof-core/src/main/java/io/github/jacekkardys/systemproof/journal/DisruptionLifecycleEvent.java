package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ComponentId;

/**
 * Core-owned immutable record of a contributed disruption lifecycle stage.
 *
 * <p>The event records a reported stage only; it does not implement or execute a disruption.
 */
public record DisruptionLifecycleEvent(
    ComponentId observingComponentId,
    DisruptionId disruptionId,
    Stage stage
) implements ScenarioEvent {
    public DisruptionLifecycleEvent {
        observingComponentId = Objects.requireNonNull(
            observingComponentId,
            "observingComponentId must not be null"
        );
        disruptionId = Objects.requireNonNull(disruptionId, "disruptionId must not be null");
        stage = Objects.requireNonNull(stage, "stage must not be null");
    }

    public enum Stage {
        DECLARED,
        ACTIVE,
        CLEARED,
        FAILED
    }
}
