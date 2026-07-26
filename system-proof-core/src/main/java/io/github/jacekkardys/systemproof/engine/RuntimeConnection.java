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
 * <p>Only the environment-owned registry can mutate lifecycle state or bind direct and consumer
 * targets. Public callers can inspect immutable metadata and detached snapshots only.
 */
public final class RuntimeConnection<C> {
    private final Connection<C> declaration;
    private final ConnectionDescriptor descriptor;
    private final RoutingMode routingMode;
    private final ConnectionRouteProvider<C> routeProvider;
    private ConnectionState state = ConnectionState.DECLARED;
    private EndpointBinding<C> directTarget;
    private EndpointBinding<C> consumerTarget;
    private ConnectionRoute<C> route;
    private boolean directTargetWasBound;

    RuntimeConnection(Connection<C> declaration) {
        this(declaration, ConnectionRouting.direct().select(declaration));
    }

    RuntimeConnection(
        Connection<C> declaration,
        ConnectionRouting.Selection<C> routing
    ) {
        this.declaration = Objects.requireNonNull(
            declaration,
            "declaration must not be null"
        );
        routing = Objects.requireNonNull(routing, "routing must not be null");
        descriptor = ConnectionDescriptor.from(declaration);
        routingMode = routing.mode();
        routeProvider = routing.provider();
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

    public synchronized boolean consumerTargetAvailable() {
        return consumerTarget != null;
    }

    public synchronized RuntimeConnectionSnapshot snapshot() {
        return new RuntimeConnectionSnapshot(
            descriptor,
            state,
            routingMode,
            directTarget != null,
            consumerTarget != null
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

    synchronized RouteOwnership<C> acquireRoute(EndpointBinding<C> target) {
        validateCanBindDirectTarget();
        target = validateTarget(target, "directTarget");
        ConnectionRoute<C> preparedRoute = Objects.requireNonNull(
            routeProvider.prepare(descriptor, target),
            "Route provider returned null for connection '" + id() + "'"
        );
        return new RouteOwnership<>(this, target, preparedRoute);
    }

    synchronized PreparedTargets<C> validateRoute(RouteOwnership<C> ownership) {
        validateCanBindDirectTarget();
        ownership = Objects.requireNonNull(ownership, "ownership must not be null");
        if (ownership.connection() != this) {
            throw new IllegalArgumentException(
                "Route ownership does not belong to connection '" + id() + "'"
            );
        }
        validateTarget(ownership.route().consumerTarget(), "consumerTarget");
        return new PreparedTargets<>(ownership);
    }

    synchronized void validateCanBind(PreparedTargets<C> prepared) {
        validateCanBindDirectTarget();
        if (Objects.requireNonNull(prepared, "prepared must not be null").connection() != this) {
            throw new IllegalArgumentException(
                "Prepared targets do not belong to connection '" + id() + "'"
            );
        }
    }

    synchronized void bindTargets(PreparedTargets<C> prepared) {
        validateCanBind(prepared);
        directTarget = prepared.directTarget();
        consumerTarget = prepared.route().consumerTarget();
        route = prepared.route();
        directTargetWasBound = true;
        transition(ConnectionState.RUNNING);
    }

    synchronized void beginStopping() {
        if (state != ConnectionState.STARTING && state != ConnectionState.RUNNING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot begin stopping from state " + state
            );
        }
        consumerTarget = null;
        transition(ConnectionState.STOPPING);
    }

    synchronized void closeRoute() throws Exception {
        if (state != ConnectionState.STOPPING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot close its route from state " + state
            );
        }
        if (route != null) {
            route.close();
        }
    }

    synchronized void invalidateDirectTarget() {
        if (state != ConnectionState.STOPPING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot invalidate its direct target from state " + state
            );
        }
        directTarget = null;
        route = null;
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
        consumerTarget = null;
        route = null;
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
        if (state != ConnectionState.RUNNING || consumerTarget == null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' has no available consumer target in state " + state
            );
        }
        return required.contract().cast(consumerTarget.internal());
    }

    synchronized EndpointBinding<C> directTarget() {
        if (directTarget == null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' has no available direct target in state " + state
            );
        }
        return directTarget;
    }

    synchronized EndpointBinding<C> consumerTarget() {
        if (consumerTarget == null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' has no available consumer target in state " + state
            );
        }
        return consumerTarget;
    }

    private EndpointBinding<C> validateTarget(
        EndpointBinding<C> target,
        String description
    ) {
        Objects.requireNonNull(target, description + " must not be null");
        declaration.from().contract().cast(target.internal());
        declaration.from().contract().cast(target.external());
        return target;
    }

    private void transition(ConnectionState next) {
        Objects.requireNonNull(next, "next must not be null");
        if (!isLegalTransition(state, next)) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot transition from " + state + " to " + next
            );
        }
        if (next == ConnectionState.RUNNING
            && (directTarget == null || consumerTarget == null)) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot run without direct and consumer targets"
            );
        }
        if (next == ConnectionState.STOPPING && consumerTarget != null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot stop with a consumer target"
            );
        }
        if (next == ConnectionState.STOPPED
            && (directTarget != null || consumerTarget != null)) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot stop with available targets"
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

    record RouteOwnership<C>(
        RuntimeConnection<C> connection,
        EndpointBinding<C> directTarget,
        ConnectionRoute<C> route
    ) {
        RouteOwnership {
            connection = Objects.requireNonNull(connection, "connection must not be null");
            directTarget = Objects.requireNonNull(
                directTarget,
                "directTarget must not be null"
            );
            route = Objects.requireNonNull(route, "route must not be null");
        }

        void closeRoute() throws Exception {
            route.close();
        }
    }

    record PreparedTargets<C>(RouteOwnership<C> ownership) {
        PreparedTargets {
            ownership = Objects.requireNonNull(ownership, "ownership must not be null");
        }

        RuntimeConnection<C> connection() {
            return ownership.connection();
        }

        EndpointBinding<C> directTarget() {
            return ownership.directTarget();
        }

        ConnectionRoute<C> route() {
            return ownership.route();
        }

        void closeRoute() throws Exception {
            ownership.closeRoute();
        }
    }
}
