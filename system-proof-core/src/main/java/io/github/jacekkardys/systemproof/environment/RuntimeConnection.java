package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.topology.Connection;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.topology.RequiredPort;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;

/**
 * Authoritative runtime materialization of one validated logical connection.
 *
 * <p>Only the environment-owned registry can mutate lifecycle state or bind direct and consumer
 * targets. A prepared route remains transaction-owned until an installation commits it here;
 * normal shutdown then closes the runtime-owned route and discards it. Observation status is
 * sampled and refreshed outside every runtime monitor, then atomically copied into a
 * framework-owned cache. Public callers inspect only that cached status in detached snapshots.
 */
final class RuntimeConnection<C> {
    private final Connection<C> declaration;
    private final ConnectionDescriptor descriptor;
    private final RoutingMode routingMode;
    private final ObservationRequirement observationRequirement;
    private final ConnectionRouting.Selection<C> routing;
    private final ConnectionObservations observations;
    private final InteractionDecisionCoordinator coordinator;
    private ConnectionState state = ConnectionState.DECLARED;
    private EffectiveObservationStatus observationStatus;
    private EndpointBinding<C> directTarget;
    private EndpointBinding<C> consumerTarget;
    private RouteOwnership<C> routeOwnership;
    private boolean directTargetWasBound;

    RuntimeConnection(
        Connection<C> declaration,
        ConnectionRouting.Selection<C> routing,
        ConnectionObservations observations,
        InteractionDecisionCoordinator coordinator
    ) {
        this.declaration = Objects.requireNonNull(
            declaration,
            "declaration must not be null"
        );
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        descriptor = ConnectionDescriptor.from(declaration);
        routingMode = routing.mode();
        observationRequirement = routing.observationRequirement();
        this.observations = Objects.requireNonNull(
            observations,
            "observations must not be null"
        );
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        observationStatus = observationRequirement == ObservationRequirement.DISABLED
            ? EffectiveObservationStatus.DISABLED
            : EffectiveObservationStatus.PENDING;
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

    public ObservationRequirement observationRequirement() {
        return observationRequirement;
    }

    Optional<RequiredObservationProfile> requiredObservationProfile() {
        return routing.requiredObservationProfile();
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
            observationRequirement,
            observationStatus,
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
            routing.prepare(
                descriptor,
                observations,
                coordinator,
                target
            ),
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
        validateTarget(routing.consumerTarget(ownership.route()), "consumerTarget");
        return new PreparedTargets<>(ownership);
    }

    synchronized void validateCanInstall(PreparedTargets<C> prepared) {
        validateCanBindDirectTarget();
        if (Objects.requireNonNull(prepared, "prepared must not be null").connection() != this) {
            throw new IllegalArgumentException(
                "Prepared targets do not belong to connection '" + id() + "'"
            );
        }
        prepared.requireTransactionOwner();
    }

    synchronized Installation<C> prepareInstallation(PreparedTargets<C> prepared) {
        validateCanInstall(prepared);
        EndpointBinding<C> preparedConsumerTarget = validateTarget(
            routing.consumerTarget(prepared.route()),
            "consumerTarget"
        );
        return new Installation<>(prepared, preparedConsumerTarget);
    }

    synchronized void bindTargets(Installation<C> installation) {
        installation = Objects.requireNonNull(installation, "installation must not be null");
        PreparedTargets<C> prepared = installation.prepared();
        validateCanInstall(prepared);
        routeOwnership = prepared.ownership();
        directTarget = prepared.directTarget();
        consumerTarget = installation.consumerTarget();
        observationStatus = initialObservationStatus();
        prepared.transferToRuntime();
        directTargetWasBound = true;
        if (observationRequirement == ObservationRequirement.DISABLED) {
            transition(ConnectionState.RUNNING);
        }
    }

    synchronized void rollbackTargets(PreparedTargets<C> prepared) throws Exception {
        Objects.requireNonNull(prepared, "prepared must not be null");
        if (prepared.connection() != this) {
            throw new IllegalArgumentException(
                "Prepared targets do not belong to connection '" + id() + "'"
            );
        }
        if (routeOwnership == prepared.ownership()) {
            prepared.reclaimForRollback();
            directTarget = null;
            consumerTarget = null;
            routeOwnership = null;
            directTargetWasBound = false;
            observationStatus = initialObservationStatus();
            if (state == ConnectionState.RUNNING) {
                state = ConnectionState.STARTING;
            } else if (state != ConnectionState.STARTING) {
                throw new IllegalStateException(
                    "Connection '" + id() + "' cannot roll back targets from state " + state
                );
            }
        }
        prepared.closeTransactionRoute();
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
        if (routeOwnership == null || routeOwnership.closed()) {
            return;
        }
        try {
            routeOwnership.closeRuntimeRoute(this);
        } finally {
            observationStatus = stoppedObservationStatus();
        }
    }

    synchronized void invalidateDirectTarget() {
        if (state != ConnectionState.STOPPING) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot invalidate its direct target from state " + state
            );
        }
        directTarget = null;
        observationStatus = stoppedObservationStatus();
        routeOwnership = null;
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
        if (routeOwnership != null && !routeOwnership.closed()) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot fail while owning an active route"
            );
        }
        directTarget = null;
        consumerTarget = null;
        routeOwnership = null;
        observationStatus = failedObservationStatus();
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

    synchronized SemanticControlCapabilityRegistry.Availability
        semanticControlAvailability() {
        if (!routing.semanticControlsDeclared()) {
            return SemanticControlCapabilityRegistry.Availability.UNSUPPORTED;
        }
        if (state == ConnectionState.DECLARED) {
            return SemanticControlCapabilityRegistry.Availability.DECLARED;
        }
        if (state != ConnectionState.RUNNING
            || routeOwnership == null
            || routeOwnership.closed()
            || !routing.semanticControlsMaterialized(routeOwnership.route())
            || observationStatus != EffectiveObservationStatus.ACTIVE) {
            return SemanticControlCapabilityRegistry.Availability.UNAVAILABLE;
        }
        return SemanticControlCapabilityRegistry.Availability.AVAILABLE;
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

    synchronized ObservationProbe startupObservationProbe() {
        if (observationRequirement == ObservationRequirement.DISABLED
            || observationStatus != EffectiveObservationStatus.PENDING) {
            return null;
        }
        if (state != ConnectionState.STARTING
            || routeOwnership == null
            || routeOwnership.closed()) {
            return null;
        }
        RouteOwnership<C> capturedOwnership = routeOwnership;
        return new ObservationProbe(
            this,
            capturedOwnership,
            () -> routing.observationStatus(capturedOwnership.route()),
            true
        );
    }

    synchronized ObservationProbe refreshObservationProbe() {
        if (observationRequirement == ObservationRequirement.DISABLED
            || state != ConnectionState.RUNNING
            || routeOwnership == null
            || routeOwnership.closed()) {
            return null;
        }
        RouteOwnership<C> capturedOwnership = routeOwnership;
        return new ObservationProbe(
            this,
            capturedOwnership,
            () -> routing.observationStatus(capturedOwnership.route()),
            false
        );
    }

    synchronized void validateStartupObservationResult(ObservationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.connection() != this
            || state != ConnectionState.STARTING
            || routeOwnership != result.ownership()
            || routeOwnership.closed()) {
            throw new IllegalStateException(
                "Observation sample no longer belongs to active connection '" + id() + "'"
            );
        }
        validateObservationStatus(result.status());
        validateSemanticControlCapability(routeOwnership.route(), result.status());
    }

    synchronized void applyStartupObservationResult(ObservationResult result) {
        validateStartupObservationResult(result);
        observationStatus = result.status();
        transition(ConnectionState.RUNNING);
    }

    synchronized void validateObservationRefresh(ObservationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.connection() != this
            || result.startup()
            || state != ConnectionState.RUNNING
            || routeOwnership != result.ownership()
            || routeOwnership.closed()) {
            throw new IllegalStateException(
                "Observation refresh no longer belongs to active connection '" + id() + "'"
            );
        }
    }

    synchronized void applyObservationRefresh(ObservationResult result) {
        validateObservationRefresh(result);
        if (observationStatus != EffectiveObservationStatus.FAILED
            && observationStatus != EffectiveObservationStatus.DEGRADED) {
            observationStatus = result.status();
        }
    }

    private void validateObservationStatus(EffectiveObservationStatus effectiveStatus) {
        Objects.requireNonNull(effectiveStatus, "effectiveStatus must not be null");
        switch (observationRequirement) {
            case DISABLED -> {
                if (effectiveStatus != EffectiveObservationStatus.DISABLED) {
                    throw new IllegalStateException(
                        "Connection '" + id()
                            + "' disabled observation but its route reported " + effectiveStatus
                    );
                }
            }
            case OPTIONAL -> {
                if (effectiveStatus != EffectiveObservationStatus.ACTIVE
                    && effectiveStatus != EffectiveObservationStatus.UNSUPPORTED) {
                    throw new IllegalStateException(
                        "Connection '" + id()
                            + "' optional observation route reported invalid initial status "
                            + effectiveStatus
                    );
                }
            }
            case REQUIRED -> {
                if (effectiveStatus != EffectiveObservationStatus.ACTIVE) {
                    throw new IllegalStateException(
                        "Connection '" + id()
                            + "' requires active observation but its route reported "
                            + effectiveStatus
                    );
                }
            }
        }
    }

    private void validateSemanticControlCapability(
        ConnectionRoute<C> route,
        EffectiveObservationStatus effectiveStatus
    ) {
        if (routing.semanticControlsDeclared()
            && (!routing.semanticControlsMaterialized(route)
                || effectiveStatus != EffectiveObservationStatus.ACTIVE)) {
            throw new IllegalStateException(
                "Connection '" + id()
                    + "' declared semantic-control capability but did not materialize it"
            );
        }
    }

    private EffectiveObservationStatus stoppedObservationStatus() {
        if (observationStatus == EffectiveObservationStatus.ACTIVE) {
            return EffectiveObservationStatus.INACTIVE;
        }
        return observationStatus;
    }

    private EffectiveObservationStatus initialObservationStatus() {
        return observationRequirement == ObservationRequirement.DISABLED
            ? EffectiveObservationStatus.DISABLED
            : EffectiveObservationStatus.PENDING;
    }

    private EffectiveObservationStatus failedObservationStatus() {
        return switch (observationStatus) {
            case DISABLED, UNSUPPORTED, DEGRADED, FAILED -> observationStatus;
            case PENDING, ACTIVE, INACTIVE -> observationRequirement
                == ObservationRequirement.DISABLED
                    ? EffectiveObservationStatus.DISABLED
                    : EffectiveObservationStatus.FAILED;
        };
    }

    private void transition(ConnectionState next) {
        Objects.requireNonNull(next, "next must not be null");
        if (!isLegalTransition(state, next)) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot transition from " + state + " to " + next
            );
        }
        if (next == ConnectionState.RUNNING
            && (directTarget == null || consumerTarget == null || routeOwnership == null)) {
            throw new IllegalStateException(
                "Connection '" + id()
                    + "' cannot run without direct target, consumer target, and route ownership"
            );
        }
        if (next == ConnectionState.STOPPING && consumerTarget != null) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot stop with a consumer target"
            );
        }
        if (next == ConnectionState.STOPPED
            && (directTarget != null || consumerTarget != null || routeOwnership != null)) {
            throw new IllegalStateException(
                "Connection '" + id() + "' cannot stop with available targets or route ownership"
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

    /** Tracks the single cleanup owner from route acquisition through terminal close. */
    static final class RouteOwnership<C> {
        private final RuntimeConnection<C> connection;
        private final EndpointBinding<C> directTarget;
        private final ConnectionRoute<C> route;
        private Owner owner = Owner.TRANSACTION;

        RouteOwnership(
            RuntimeConnection<C> connection,
            EndpointBinding<C> directTarget,
            ConnectionRoute<C> route
        ) {
            this.connection = Objects.requireNonNull(
                connection,
                "connection must not be null"
            );
            this.directTarget = Objects.requireNonNull(
                directTarget,
                "directTarget must not be null"
            );
            this.route = Objects.requireNonNull(route, "route must not be null");
        }

        RuntimeConnection<C> connection() {
            return connection;
        }

        EndpointBinding<C> directTarget() {
            return directTarget;
        }

        ConnectionRoute<C> route() {
            return route;
        }

        synchronized void requireTransactionOwner() {
            if (owner != Owner.TRANSACTION) {
                throw new IllegalStateException(
                    "Route for connection '" + connection.id()
                        + "' is not owned by an installation transaction"
                );
            }
        }

        synchronized void transferToRuntime(RuntimeConnection<C> expectedConnection) {
            requireConnection(expectedConnection);
            requireTransactionOwner();
            owner = Owner.RUNTIME;
        }

        synchronized void reclaimForRollback(RuntimeConnection<C> expectedConnection) {
            requireConnection(expectedConnection);
            if (owner == Owner.RUNTIME) {
                owner = Owner.TRANSACTION;
                return;
            }
            requireTransactionOwner();
        }

        synchronized void closeTransactionRoute() throws Exception {
            if (owner == Owner.CLOSED) {
                return;
            }
            requireTransactionOwner();
            owner = Owner.CLOSED;
            connection.routing.close(route);
        }

        synchronized void closeRuntimeRoute(RuntimeConnection<C> expectedConnection)
            throws Exception {
            requireConnection(expectedConnection);
            if (owner == Owner.CLOSED) {
                return;
            }
            if (owner != Owner.RUNTIME) {
                throw new IllegalStateException(
                    "Route for connection '" + connection.id() + "' is not runtime-owned"
                );
            }
            owner = Owner.CLOSED;
            connection.routing.close(route);
        }

        synchronized boolean closed() {
            return owner == Owner.CLOSED;
        }

        private void requireConnection(RuntimeConnection<C> expectedConnection) {
            if (connection != expectedConnection) {
                throw new IllegalArgumentException(
                    "Route ownership does not belong to connection '"
                        + expectedConnection.id() + "'"
                );
            }
        }

        private enum Owner {
            TRANSACTION,
            RUNTIME,
            CLOSED
        }
    }

    /** Transaction-owned route awaiting materialization and an explicit runtime transfer. */
    static final class PreparedTargets<C> {
        private final RouteOwnership<C> ownership;

        PreparedTargets(RouteOwnership<C> ownership) {
            this.ownership = Objects.requireNonNull(
                ownership,
                "ownership must not be null"
            );
        }

        RouteOwnership<C> ownership() {
            return ownership;
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

        void requireTransactionOwner() {
            ownership.requireTransactionOwner();
        }

        void transferToRuntime() {
            ownership.transferToRuntime(connection());
        }

        void reclaimForRollback() {
            ownership.reclaimForRollback(connection());
        }

        void rollbackRoute() throws Exception {
            connection().rollbackTargets(this);
        }

        void closeTransactionRoute() throws Exception {
            ownership.closeTransactionRoute();
        }
    }

    /** Fully materialized values whose commit performs no extension SPI calls. */
    record Installation<C>(
        PreparedTargets<C> prepared,
        EndpointBinding<C> consumerTarget
    ) {
        Installation {
            prepared = Objects.requireNonNull(prepared, "prepared must not be null");
            consumerTarget = Objects.requireNonNull(
                consumerTarget,
                "consumerTarget must not be null"
            );
        }

        RuntimeConnection<C> connection() {
            return prepared.connection();
        }
    }

    /** Immutable handle captured under locks and evaluated only after all locks are released. */
    record ObservationProbe(
        RuntimeConnection<?> connection,
        RouteOwnership<?> ownership,
        Supplier<EffectiveObservationStatus> sampler,
        boolean startup
    ) {
        ObservationProbe {
            Objects.requireNonNull(connection, "connection must not be null");
            Objects.requireNonNull(ownership, "ownership must not be null");
            Objects.requireNonNull(sampler, "sampler must not be null");
        }

        ObservationResult evaluate() {
            EffectiveObservationStatus status;
            try {
                status = sampler.get();
            } catch (RuntimeException | Error failure) {
                if (startup) {
                    throw failure;
                }
                status = connection.observationRequirement == ObservationRequirement.OPTIONAL
                    ? EffectiveObservationStatus.DEGRADED
                    : EffectiveObservationStatus.FAILED;
            }
            return new ObservationResult(
                connection,
                ownership,
                status,
                startup
            );
        }
    }

    /** Detached result of one extension callback, ready for an atomic framework-owned commit. */
    record ObservationResult(
        RuntimeConnection<?> connection,
        RouteOwnership<?> ownership,
        EffectiveObservationStatus status,
        boolean startup
    ) {
        ObservationResult {
            Objects.requireNonNull(connection, "connection must not be null");
            Objects.requireNonNull(ownership, "ownership must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }
}
