package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;

/**
 * One environment-owned materialization of the immutable topology connection declarations.
 *
 * <p>Route preparation and extension-provided installation values remain transaction-owned until
 * every entry in a provider batch is ready. The registry then commits the batch without invoking
 * extension SPI and rolls every acquired route back in reverse order if any step fails.
 */
final class RuntimeConnectionRegistry {
    private final RuntimeConnectionCatalog catalog;
    private final EnvironmentEventPublisher events;

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventPublisher events
    ) {
        this(
            declarations,
            events,
            ConnectionRouting.direct(),
            new ImmediateForwardDecisionCoordinator(),
            new ProofSubjectRegistry(events)
        );
    }

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventPublisher events,
        ConnectionRouting routing
    ) {
        this(
            declarations,
            events,
            routing,
            new ImmediateForwardDecisionCoordinator(),
            new ProofSubjectRegistry(events)
        );
    }

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventPublisher events,
        ConnectionRouting routing,
        ProofSubjectRegistry proofSubjects
    ) {
        this(
            declarations,
            events,
            routing,
            new ImmediateForwardDecisionCoordinator(),
            proofSubjects
        );
    }

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventPublisher events,
        ConnectionRouting routing,
        InteractionDecisionCoordinator coordinator
    ) {
        this(
            declarations,
            events,
            routing,
            coordinator,
            new ProofSubjectRegistry(events)
        );
    }

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventPublisher events,
        ConnectionRouting routing,
        InteractionDecisionCoordinator coordinator,
        ProofSubjectRegistry proofSubjects
    ) {
        this(
            declarations,
            events,
            routing,
            coordinator,
            proofSubjects,
            new SemanticControlCapabilityRegistry()
        );
    }

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventPublisher events,
        ConnectionRouting routing,
        InteractionDecisionCoordinator coordinator,
        ProofSubjectRegistry proofSubjects,
        SemanticControlCapabilityRegistry controlCapabilities
    ) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        catalog = new RuntimeConnectionCatalog(
            declarations,
            events,
            routing,
            coordinator,
            proofSubjects
        );
        Objects.requireNonNull(
            controlCapabilities,
            "controlCapabilities must not be null"
        );
        catalog.all().forEach(connection -> controlCapabilities.register(
            connection.id(),
            connection::semanticControlAvailability,
            connection.requiredObservationProfile()
        ));
        catalog.all().forEach(this::recordLifecycle);
    }

    synchronized void beginStartup() {
        catalog.all().forEach(connection -> requireState(connection, ConnectionState.DECLARED));
        for (RuntimeConnection<?> connection : catalog.all()) {
            connection.beginStartup();
            recordLifecycle(connection);
        }
    }

    synchronized List<RuntimeConnectionSnapshot> snapshots() {
        return catalog.all().stream().map(RuntimeConnection::snapshot).toList();
    }

    synchronized RuntimeConnectionSnapshot snapshot(ConnectionId id) {
        return catalog.connection(id).snapshot();
    }

    synchronized RuntimeConnection<?> connection(ConnectionId id) {
        return catalog.connection(id);
    }

    synchronized <T> T resolve(RequiredPort<T> required) {
        return catalog.connection(required).resolve(required);
    }

    synchronized List<RuntimeConnection.PreparedTargets<?>> prepareTargets(
        Component provider,
        ComponentRuntime<?> runtime
    ) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        RuntimeEndpointBindings endpointBindings = new RuntimeEndpointBindings();
        runtime.publishBindingsTo(endpointBindings);
        List<RuntimeConnection.PreparedTargets<?>> prepared = new ArrayList<>();
        for (RuntimeConnection<?> connection : targeting(provider)) {
            try {
                prepared.add(prepareTargets(connection, endpointBindings));
            } catch (RuntimeException | Error failure) {
                if (connection.routingMode() == RoutingMode.ROUTED) {
                    events.protectRoutePreparationFailure(
                        connection.declaration(),
                        failure
                    );
                }
                rollbackPreparedRoutes(prepared, failure);
                throw failure;
            }
        }
        return List.copyOf(prepared);
    }

    synchronized void bindTargets(
        List<RuntimeConnection.PreparedTargets<?>> preparedTargets
    ) {
        Objects.requireNonNull(preparedTargets, "preparedTargets must not be null");
        List<RuntimeConnection.PreparedTargets<?>> transaction = new ArrayList<>();
        preparedTargets.stream()
            .filter(Objects::nonNull)
            .forEach(transaction::add);
        try {
            Set<RuntimeConnection<?>> unique = Collections.newSetFromMap(
                new IdentityHashMap<>()
            );
            for (RuntimeConnection.PreparedTargets<?> prepared : preparedTargets) {
                Objects.requireNonNull(prepared, "prepared target must not be null");
                if (!catalog.owns(prepared.connection())) {
                    throw new IllegalArgumentException(
                        "Connection '" + prepared.connection().id()
                            + "' is outside this runtime registry"
                    );
                }
                if (!unique.add(prepared.connection())) {
                    throw new IllegalStateException(
                        "Connection '" + prepared.connection().id()
                            + "' was prepared more than once"
                    );
                }
                validatePrepared(prepared);
            }
            List<RuntimeConnection.Installation<?>> installations =
                prepareInstallations(preparedTargets);
            for (RuntimeConnection.Installation<?> installation : installations) {
                bindPrepared(installation);
            }
        } catch (RuntimeException | Error failure) {
            rollbackPreparedRoutes(transaction, failure);
            throw failure;
        }
        preparedTargets.forEach(prepared -> recordLifecycle(prepared.connection()));
    }

    synchronized void failProviderMaterialization(Component provider, Throwable failure) {
        List<RuntimeConnection<?>> targeted = targeting(provider);
        closeCommittedRoutesForFailure(targeted, failure);
        for (RuntimeConnection<?> connection : targeted) {
            failMaterialization(connection, failure);
        }
    }

    synchronized void failProvidedPortMaterialization(
        ProvidedPort<?> provided,
        Throwable failure
    ) {
        Objects.requireNonNull(provided, "provided must not be null");
        for (RuntimeConnection<?> connection : targeting(provided)) {
            failMaterialization(connection, failure);
        }
    }

    synchronized Throwable beginProviderCleanup(Component provider) {
        List<RuntimeConnection<?>> targeted = targeting(provider);
        for (RuntimeConnection<?> connection : targeted) {
            if (connection.state() == ConnectionState.RUNNING
                || connection.state() == ConnectionState.STARTING) {
                connection.beginStopping();
                recordLifecycle(connection);
            }
        }
        Throwable failure = closeRoutesReverse(targeted);
        invalidateDirectTargets(targeted);
        return failure;
    }

    synchronized void completeProviderCleanup(Component provider) {
        for (RuntimeConnection<?> connection : targeting(provider)) {
            if (connection.state() == ConnectionState.STOPPING) {
                connection.completeStopping();
                recordLifecycle(connection);
            }
        }
    }

    synchronized void failProviderCleanup(Component provider, Throwable failure) {
        for (RuntimeConnection<?> connection : targeting(provider)) {
            if (connection.state() == ConnectionState.STOPPING) {
                connection.fail();
                recordLifecycle(connection);
                events.connectionCleanupFailure(connection.declaration(), failure);
            }
        }
    }

    synchronized Throwable stopRemaining() {
        for (RuntimeConnection<?> connection : catalog.all()) {
            switch (connection.state()) {
                case DECLARED -> {
                    connection.stopBeforeStartup();
                    recordLifecycle(connection);
                }
                case STARTING, RUNNING -> {
                    connection.beginStopping();
                    recordLifecycle(connection);
                }
                case STOPPING -> {}
                case FAILED, STOPPED -> {
                    // Failed connections are already terminal and unavailable.
                }
            }
        }
        Throwable failure = closeRoutesReverse(catalog.all());
        invalidateDirectTargets(catalog.all());
        for (RuntimeConnection<?> connection : catalog.all()) {
            if (connection.state() == ConnectionState.STOPPING) {
                connection.completeStopping();
                recordLifecycle(connection);
            }
        }
        return failure;
    }

    private void failMaterialization(RuntimeConnection<?> connection, Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        if (connection.state() == ConnectionState.DECLARED
            || connection.state() == ConnectionState.STARTING
            || connection.state() == ConnectionState.RUNNING
            || connection.state() == ConnectionState.STOPPING) {
            connection.fail();
            recordLifecycle(connection);
            events.connectionMaterializationFailure(connection.declaration(), failure);
        }
    }

    private List<RuntimeConnection<?>> targeting(Component provider) {
        return catalog.targeting(provider);
    }

    private List<RuntimeConnection<?>> targeting(ProvidedPort<?> provided) {
        return catalog.targeting(provided);
    }

    private void recordLifecycle(RuntimeConnection<?> connection) {
        events.connectionLifecycle(
            connection.declaration(),
            connection.descriptor(),
            connection.state(),
            connection.routingMode(),
            connection.directTargetAvailable(),
            connection.consumerTargetAvailable()
        );
    }

    private RuntimeConnection.PreparedTargets<?> prepareTargets(
        RuntimeConnection<?> connection,
        RuntimeEndpointBindings endpointBindings
    ) {
        return prepareTyped(connection, endpointBindings);
    }

    private <C> RuntimeConnection.PreparedTargets<C> prepareTyped(
        RuntimeConnection<C> connection,
        RuntimeEndpointBindings endpointBindings
    ) {
        connection.validateCanBindDirectTarget();
        EndpointBinding<C> target = endpointBindings.binding(connection.declaration().to());
        RuntimeConnection.RouteOwnership<C> ownership = connection.acquireRoute(target);
        try {
            return connection.validateRoute(ownership);
        } catch (RuntimeException | Error failure) {
            closeRejectedRoute(ownership, failure);
            throw failure;
        }
    }

    private static void validatePrepared(
        RuntimeConnection.PreparedTargets<?> prepared
    ) {
        validatePreparedTyped(prepared);
    }

    private static <C> void validatePreparedTyped(
        RuntimeConnection.PreparedTargets<C> prepared
    ) {
        prepared.connection().validateCanInstall(prepared);
    }

    private List<RuntimeConnection.Installation<?>> prepareInstallations(
        List<RuntimeConnection.PreparedTargets<?>> preparedTargets
    ) {
        List<RuntimeConnection.Installation<?>> installations = new ArrayList<>();
        for (RuntimeConnection.PreparedTargets<?> prepared : preparedTargets) {
            try {
                installations.add(prepareInstallation(prepared));
            } catch (RuntimeException | Error failure) {
                if (prepared.connection().routingMode() == RoutingMode.ROUTED) {
                    events.protectRoutePreparationFailure(
                        prepared.connection().declaration(),
                        failure
                    );
                }
                throw failure;
            }
        }
        return installations;
    }

    private static RuntimeConnection.Installation<?> prepareInstallation(
        RuntimeConnection.PreparedTargets<?> prepared
    ) {
        return prepareInstallationTyped(prepared);
    }

    private static <C> RuntimeConnection.Installation<C> prepareInstallationTyped(
        RuntimeConnection.PreparedTargets<C> prepared
    ) {
        return prepared.connection().prepareInstallation(prepared);
    }

    private static void bindPrepared(RuntimeConnection.Installation<?> installation) {
        bindTyped(installation);
    }

    private static <C> void bindTyped(RuntimeConnection.Installation<C> installation) {
        installation.connection().bindTargets(installation);
    }

    private static void requireState(
        RuntimeConnection<?> connection,
        ConnectionState expected
    ) {
        if (connection.state() != expected) {
            throw new IllegalStateException(
                "Connection '" + connection.id() + "' has state " + connection.state()
                    + ", expected " + expected
            );
        }
    }

    private void rollbackPreparedRoutes(
        List<RuntimeConnection.PreparedTargets<?>> prepared,
        Throwable startupFailure
    ) {
        List<RuntimeConnection.PreparedTargets<?>> reverse = new ArrayList<>(prepared);
        Collections.reverse(reverse);
        for (RuntimeConnection.PreparedTargets<?> targets : reverse) {
            try {
                targets.rollbackRoute();
            } catch (Exception | Error cleanupFailure) {
                EnvironmentRuntimeFailures.accumulate(startupFailure, cleanupFailure);
                events.protectRouteCleanupFailure(
                    targets.connection().declaration(),
                    cleanupFailure
                );
                events.connectionCleanupFailure(
                    targets.connection().declaration(),
                    cleanupFailure
                );
            }
        }
    }

    private void closeRejectedRoute(
        RuntimeConnection.RouteOwnership<?> ownership,
        Throwable preparationFailure
    ) {
        try {
            ownership.closeTransactionRoute();
        } catch (Exception | Error cleanupFailure) {
            EnvironmentRuntimeFailures.accumulate(preparationFailure, cleanupFailure);
            events.protectRouteCleanupFailure(
                ownership.connection().declaration(),
                cleanupFailure
            );
            events.connectionCleanupFailure(
                ownership.connection().declaration(),
                cleanupFailure
            );
        }
    }

    private void closeCommittedRoutesForFailure(
        List<RuntimeConnection<?>> targeted,
        Throwable startupFailure
    ) {
        Objects.requireNonNull(startupFailure, "startupFailure must not be null");
        for (RuntimeConnection<?> connection : targeted) {
            if (connection.state() == ConnectionState.RUNNING) {
                connection.beginStopping();
                recordLifecycle(connection);
            }
        }
        List<RuntimeConnection<?>> reverse = new ArrayList<>(targeted);
        Collections.reverse(reverse);
        for (RuntimeConnection<?> connection : reverse) {
            if (connection.state() != ConnectionState.STOPPING) {
                continue;
            }
            try {
                connection.closeRoute();
            } catch (Exception | Error cleanupFailure) {
                EnvironmentRuntimeFailures.accumulate(startupFailure, cleanupFailure);
                events.protectRouteCleanupFailure(
                    connection.declaration(),
                    cleanupFailure
                );
                events.connectionCleanupFailure(
                    connection.declaration(),
                    cleanupFailure
                );
            }
        }
        invalidateDirectTargets(targeted);
    }

    private Throwable closeRoutesReverse(List<RuntimeConnection<?>> targeted) {
        Throwable firstFailure = null;
        List<RuntimeConnection<?>> reverse = new ArrayList<>(targeted);
        Collections.reverse(reverse);
        for (RuntimeConnection<?> connection : reverse) {
            if (connection.state() != ConnectionState.STOPPING) {
                continue;
            }
            try {
                connection.closeRoute();
            } catch (Exception | Error cleanupFailure) {
                events.protectRouteCleanupFailure(
                    connection.declaration(),
                    cleanupFailure
                );
                connection.fail();
                recordLifecycle(connection);
                events.connectionCleanupFailure(
                    connection.declaration(),
                    cleanupFailure
                );
                firstFailure = EnvironmentRuntimeFailures.accumulate(
                    firstFailure,
                    cleanupFailure
                );
            }
        }
        return firstFailure;
    }

    private static void invalidateDirectTargets(List<RuntimeConnection<?>> targeted) {
        for (RuntimeConnection<?> connection : targeted) {
            if (connection.state() == ConnectionState.STOPPING) {
                connection.invalidateDirectTarget();
            }
        }
    }
}
