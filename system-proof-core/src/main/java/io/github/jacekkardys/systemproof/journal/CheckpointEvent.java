package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.component.ComponentId;

/**
 * Core-owned immutable checkpoint or barrier record.
 *
 * <p>A recorded stage is a local journal fact only. It does not prove external ordering,
 * checkpoint evaluation, barrier satisfaction, or causality.
 */
public record CheckpointEvent(
    ComponentId observingComponentId,
    CheckpointId checkpointId,
    Kind kind,
    Stage stage
) implements ScenarioEvent {
    public CheckpointEvent {
        observingComponentId = Objects.requireNonNull(
            observingComponentId,
            "observingComponentId must not be null"
        );
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        stage = Objects.requireNonNull(stage, "stage must not be null");
    }

    public enum Kind {
        CHECKPOINT,
        BARRIER
    }

    public enum Stage {
        DECLARED,
        OBSERVED,
        CLEARED
    }
}
