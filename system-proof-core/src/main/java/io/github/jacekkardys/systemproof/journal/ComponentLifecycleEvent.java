package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentState;

/** A structured lifecycle transition for one stable component identity. */
public record ComponentLifecycleEvent(
    ComponentId componentId,
    ComponentState state
) implements ScenarioEvent {
    public ComponentLifecycleEvent {
        componentId = Objects.requireNonNull(componentId, "componentId must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
    }
}
