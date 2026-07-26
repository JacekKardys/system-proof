package io.github.jacekkardys.systemproof.model;

import java.util.Objects;

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
