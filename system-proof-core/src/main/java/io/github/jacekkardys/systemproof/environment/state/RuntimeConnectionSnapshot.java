package io.github.jacekkardys.systemproof.environment.state;

import java.util.Objects;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Detached immutable public state of one materialized runtime connection. */
public record RuntimeConnectionSnapshot(
    ConnectionDescriptor descriptor,
    ConnectionState state,
    RoutingMode routingMode,
    ObservationRequirement observationRequirement,
    EffectiveObservationStatus effectiveObservationStatus,
    boolean directTargetAvailable,
    boolean consumerTargetAvailable
) {
    public RuntimeConnectionSnapshot {
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        routingMode = Objects.requireNonNull(routingMode, "routingMode must not be null");
        observationRequirement = Objects.requireNonNull(
            observationRequirement,
            "observationRequirement must not be null"
        );
        effectiveObservationStatus = Objects.requireNonNull(
            effectiveObservationStatus,
            "effectiveObservationStatus must not be null"
        );
    }

    public ConnectionId id() {
        return descriptor.id();
    }
}
