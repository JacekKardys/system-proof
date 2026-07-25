package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.ConnectionState;
import io.github.jacekkardys.systemproof.model.RoutingMode;

/** Immutable semantic record of one runtime connection lifecycle state. */
public record ConnectionLifecycleEvent(
    ConnectionDescriptor connection,
    ConnectionState state,
    RoutingMode routingMode,
    boolean directTargetAvailable,
    boolean consumerTargetAvailable
) implements ScenarioEvent {
    public ConnectionLifecycleEvent {
        connection = Objects.requireNonNull(connection, "connection must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        routingMode = Objects.requireNonNull(routingMode, "routingMode must not be null");
    }
}
