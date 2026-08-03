package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;

/**
 * Typed effective target and optional framework-owned resource prepared for one consumer.
 *
 * <p>Endpoint values and the owned resource remain runtime-internal after construction. Once the
 * provider returns this route, installation rollback or the committed runtime connection closes
 * it exactly once; a cleanup exception does not make the resource eligible for an implicit retry.
 */
public final class ConnectionRoute<C> {
    private final EndpointBinding<C> consumerTarget;
    private final ObservationStatusProvider observationStatusProvider;
    private final AutoCloseable resource;
    private boolean closed;

    private ConnectionRoute(
        EndpointBinding<C> consumerTarget,
        ObservationStatusProvider observationStatusProvider,
        AutoCloseable resource
    ) {
        this.consumerTarget = Objects.requireNonNull(
            consumerTarget,
            "consumerTarget must not be null"
        );
        this.observationStatusProvider = Objects.requireNonNull(
            observationStatusProvider,
            "observationStatusProvider must not be null"
        );
        this.resource = resource;
    }

    /** Creates a routed target without an owned closeable resource. */
    public static <C> ConnectionRoute<C> routed(EndpointBinding<C> consumerTarget) {
        return new ConnectionRoute<>(
            consumerTarget,
            () -> EffectiveObservationStatus.DISABLED,
            null
        );
    }

    /** Creates a routed target with one resource owned by this connection. */
    public static <C> ConnectionRoute<C> routed(
        EndpointBinding<C> consumerTarget,
        AutoCloseable resource
    ) {
        return new ConnectionRoute<>(
            consumerTarget,
            () -> EffectiveObservationStatus.DISABLED,
            Objects.requireNonNull(resource, "resource must not be null")
        );
    }

    /** Creates a routed target with explicit dynamic observation state and one owned resource. */
    public static <C> ConnectionRoute<C> routed(
        EndpointBinding<C> consumerTarget,
        ObservationStatusProvider observationStatusProvider,
        AutoCloseable resource
    ) {
        return new ConnectionRoute<>(
            consumerTarget,
            observationStatusProvider,
            Objects.requireNonNull(resource, "resource must not be null")
        );
    }

    static <C> ConnectionRoute<C> direct(EndpointBinding<C> directTarget) {
        return new ConnectionRoute<>(
            directTarget,
            () -> EffectiveObservationStatus.DISABLED,
            null
        );
    }

    EndpointBinding<C> consumerTarget() {
        return consumerTarget;
    }

    EffectiveObservationStatus observationStatus() {
        return Objects.requireNonNull(
            observationStatusProvider.observationStatus(),
            "Route observation status must not be null"
        );
    }

    synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        if (resource != null) {
            resource.close();
        }
    }
}
