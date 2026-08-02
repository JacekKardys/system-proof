package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;

/** A structured environment lifecycle transition. */
public record EnvironmentLifecycleEvent(EnvironmentState state) implements ScenarioEvent {
    public EnvironmentLifecycleEvent {
        state = Objects.requireNonNull(state, "state must not be null");
    }
}
