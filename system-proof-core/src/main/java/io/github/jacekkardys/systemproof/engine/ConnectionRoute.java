package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.EndpointBinding;

/**
 * Typed effective target and optional connection-owned resource prepared for one consumer.
 *
 * <p>Endpoint values and the owned resource remain runtime-internal after construction.
 */
public final class ConnectionRoute<C> {
    private final EndpointBinding<C> consumerTarget;
    private final AutoCloseable resource;
    private boolean closed;

    private ConnectionRoute(
        EndpointBinding<C> consumerTarget,
        AutoCloseable resource
    ) {
        this.consumerTarget = Objects.requireNonNull(
            consumerTarget,
            "consumerTarget must not be null"
        );
        this.resource = resource;
    }

    /** Creates a routed target without an owned closeable resource. */
    public static <C> ConnectionRoute<C> routed(EndpointBinding<C> consumerTarget) {
        return new ConnectionRoute<>(consumerTarget, null);
    }

    /** Creates a routed target with one resource owned by this connection. */
    public static <C> ConnectionRoute<C> routed(
        EndpointBinding<C> consumerTarget,
        AutoCloseable resource
    ) {
        return new ConnectionRoute<>(
            consumerTarget,
            Objects.requireNonNull(resource, "resource must not be null")
        );
    }

    static <C> ConnectionRoute<C> direct(EndpointBinding<C> directTarget) {
        return new ConnectionRoute<>(directTarget, null);
    }

    EndpointBinding<C> consumerTarget() {
        return consumerTarget;
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
