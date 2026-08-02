package io.github.jacekkardys.systemproof.routing;

import java.util.Objects;
import io.github.jacekkardys.systemproof.observation.ConnectionObservations;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.model.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.model.runtime.ObservationRequirement;

/**
 * Immutable preparation input for one exact materialized runtime connection.
 *
 * <p>The context exposes semantic connection metadata, the bound observation capability, and the
 * typed direct target. It exposes no journal, topology mutation, socket, container, or mutable
 * runtime state.
 */
public final class ConnectionRouteContext<C> {
    private final ConnectionDescriptor connection;
    private final ObservationRequirement observationRequirement;
    private final ConnectionObservations observations;
    private final InteractionDecisionCoordinator coordinator;
    private final EndpointBinding<C> directTarget;

    ConnectionRouteContext(
        ConnectionDescriptor connection,
        ObservationRequirement observationRequirement,
        ConnectionObservations observations,
        InteractionDecisionCoordinator coordinator,
        EndpointBinding<C> directTarget
    ) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.observationRequirement = Objects.requireNonNull(
            observationRequirement,
            "observationRequirement must not be null"
        );
        this.observations = Objects.requireNonNull(
            observations,
            "observations must not be null"
        );
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.directTarget = Objects.requireNonNull(directTarget, "directTarget must not be null");
    }

    public ConnectionDescriptor connection() {
        return connection;
    }

    public ConnectionObservations observations() {
        return observations;
    }

    public ObservationRequirement observationRequirement() {
        return observationRequirement;
    }

    public InteractionDecisionCoordinator coordinator() {
        return coordinator;
    }

    public EndpointBinding<C> directTarget() {
        return directTarget;
    }
}
