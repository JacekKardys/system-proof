package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.ConnectionState;
import io.github.jacekkardys.systemproof.model.EndpointBinding;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RoutingMode;
import io.github.jacekkardys.systemproof.model.RuntimeConnectionSnapshot;

/**
 * Authoritative runtime materialization of one validated logical connection.
 *
 * <p>Only the environment-owned registry can mutate lifecycle state or bind the direct target.
 * Public callers can inspect immutable metadata and detached snapshots only.
 */
public final class RuntimeConnection<C> {
    private final Connection<C> declaration;
    private final ConnectionDescriptor descriptor;
    private final RoutingMode routingMode = RoutingMode.DIRECT;
    private ConnectionState state = ConnectionState.DECLARED;
    private EndpointBinding<C> directTarget;
    private boolean directTargetWasBound;

    RuntimeConnection(Connection<C> declaration) {
        this.declaration = Objects.requireNonNull(
            declaration,
            "declaration must not be null"
        );
        descriptor = ConnectionDescriptor.from(declaration);
    }

    public Connection<C> declaration() {
        return declaration;
    }

    public ConnectionId id() {
        return descriptor.id();
    }

    public ConnectionDescriptor descriptor() {
        return descriptor;
    }

    public ComponentId sourceComponentId() {
        return descriptor.sourceComponentId();
    }

    public String sourceRequiredPortName() {
        return descriptor.sourceRequiredPortName();
    }

    public ComponentId targetComponentId() {
        return descriptor.targetComponentId();
    }

    public String targetProvidedPortName() {
        return descriptor.targetProvidedPortName();
    }

    public String contractId() {
        return descriptor.contractId();
    }

    public Class<C> contractType() {
        return declaration.from().contractType();
    }

    public String interactionId() {
        return descriptor.interactionId();
    }

    public String protocolId() {
        return descriptor.protocolId();
    }

    public String protocolScheme() {
        return descriptor.protocolScheme();
    }

    public synchronized ConnectionState state() {
        return state;
    }

    public RoutingMode routingMode() {
        return routingMode;
    }

    public synchronized boolean directTargetAvailable() {
        return directTarget != null;
    }

    public synchronized RuntimeConnectionSnapshot snapshot() {
        return new RuntimeConnectionSnapshot(
            descriptor,
            state,
            routingMode,
            directTarget != null
        );
    }

    synchronized void beginStartup() {
        transition(ConnectionState.STARTING);
    }

    synchronized void validateCanBindDirectTarget() {
        if (state != ConnectionState.STARTING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot bind a direct target from state " + state
            );
        }
        if (directTargetWasBound || directTarget != null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' already materialized its direct target"
            );
        }
    }

    synchronized void bindDirectTarget(EndpointBinding<C> target) {
        validateCanBindDirectTarget();
        directTarget = Objects.requireNonNull(target, "target must not be null");
        directTargetWasBound = true;
        transition(ConnectionState.RUNNING);
    }

    synchronized void beginStopping() {
        if (state != ConnectionState.STARTING && state != ConnectionState.RUNNING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot begin stopping from state " + state
            );
        }
        directTarget = null;
        transition(ConnectionState.STOPPING);
    }

    synchronized void completeStopping() {
        if (state != ConnectionState.STOPPING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot complete stopping from state " + state
            );
        }
        transition(ConnectionState.STOPPED);
    }

    synchronized void stopBeforeStartup() {
        if (state != ConnectionState.DECLARED) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot stop before startup from state " + state
            );
        }
        transition(ConnectionState.STOPPED);
    }

    synchronized void fail() {
        directTarget = null;
        transition(ConnectionState.FAILED);
    }

    synchronized <T> T resolve(RequiredPort<T> required) {
        Objects.requireNonNull(required, "required must not be null");
        if (required != declaration.from()) {
            throw new IllegalArgumentException(
                "Required port '" + required.qualifiedName()
                    + "' does not belong to connection '" + id() + "'"
            );
        }
        if (state != ConnectionState.RUNNING || directTarget == null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' has no available direct target in state " + state
            );
        }
        return required.contract().cast(directTarget.internal());
    }

    synchronized EndpointBinding<C> directTarget() {
        if (directTarget == null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' has no available direct target in state " + state
            );
        }
        return directTarget;
    }

    private void transition(ConnectionState next) {
        Objects.requireNonNull(next, "next must not be null");
        if (!isLegalTransition(state, next)) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot transition from " + state + " to " + next
            );
        }
        if (next == ConnectionState.RUNNING && directTarget == null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot run without a direct target"
            );
        }
        if (next == ConnectionState.STOPPED && directTarget != null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot stop with a direct target"
            );
        }
        state = next;
    }

    private static boolean isLegalTransition(
        ConnectionState current,
        ConnectionState next
    ) {
        return switch (current) {
            case DECLARED -> next == ConnectionState.STARTING
                || next == ConnectionState.STOPPED
                || next == ConnectionState.FAILED;
            case STARTING -> next == ConnectionState.RUNNING
                || next == ConnectionState.STOPPING
                || next == ConnectionState.FAILED;
            case RUNNING -> next == ConnectionState.STOPPING
                || next == ConnectionState.FAILED;
            case STOPPING -> next == ConnectionState.STOPPED
                || next == ConnectionState.FAILED;
            case FAILED -> false;
            case STOPPED -> false;
        };
    }
}
