package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.EndpointBinding;

/**
 * Immutable preparation input for one exact materialized runtime connection.
 *
 * <p>The context exposes semantic connection metadata, the bound observation capability, and the
 * typed direct target. It exposes no journal, topology mutation, socket, container, or mutable
 * runtime state.
 */
public final class ConnectionRouteContext<C> {
    private final ConnectionDescriptor connection;
    private final ConnectionObservations observations;
    private final EndpointBinding<C> directTarget;

    ConnectionRouteContext(
        ConnectionDescriptor connection,
        ConnectionObservations observations,
        EndpointBinding<C> directTarget
    ) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.observations = Objects.requireNonNull(
            observations,
            "observations must not be null"
        );
        this.directTarget = Objects.requireNonNull(directTarget, "directTarget must not be null");
    }

    public ConnectionDescriptor connection() {
        return connection;
    }

    public ConnectionObservations observations() {
        return observations;
    }

    public EndpointBinding<C> directTarget() {
        return directTarget;
    }
}
