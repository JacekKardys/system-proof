package io.github.jacekkardys.systemproof.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.ConnectionState;
import io.github.jacekkardys.systemproof.model.EndpointBinding;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RuntimeConnectionSnapshot;

/** One environment-owned materialization of the immutable topology connection declarations. */
final class RuntimeConnectionRegistry {
    private final List<RuntimeConnection<?>> connections;
    private final Map<ConnectionId, RuntimeConnection<?>> connectionsById;
    private final IdentityHashMap<RequiredPort<?>, RuntimeConnection<?>> connectionsByRequired =
        new IdentityHashMap<>();
    private final IdentityHashMap<Component, List<RuntimeConnection<?>>> connectionsByProvider =
        new IdentityHashMap<>();
    private final IdentityHashMap<ProvidedPort<?>, List<RuntimeConnection<?>>> connectionsByProvided =
        new IdentityHashMap<>();
    private final EnvironmentEventLog eventLog;

    RuntimeConnectionRegistry(
        List<ConnectionRef> declarations,
        EnvironmentEventLog eventLog
    ) {
        Objects.requireNonNull(declarations, "declarations must not be null");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog must not be null");

        List<RuntimeConnection<?>> materialized = new ArrayList<>(declarations.size());
        Map<ConnectionId, RuntimeConnection<?>> byId = new LinkedHashMap<>();
        for (ConnectionRef declaration : declarations) {
            RuntimeConnection<?> connection = materialize(
                Objects.requireNonNull(declaration, "declaration must not be null")
            );
            RuntimeConnection<?> duplicateId = byId.putIfAbsent(connection.id(), connection);
            if (duplicateId != null) {
                throw new IllegalStateException(
                    "Runtime connection '" + connection.id() + "' was materialized more than once"
                );
            }
            RuntimeConnection<?> duplicateRequired = connectionsByRequired.putIfAbsent(
                connection.declaration().from(),
                connection
            );
            if (duplicateRequired != null) {
                throw new IllegalStateException(
                    "Required port '" + connection.declaration().from().qualifiedName()
                        + "' was materialized by more than one runtime connection"
                );
            }
            connectionsByProvider.computeIfAbsent(
                connection.declaration().to().owner(),
                ignored -> new ArrayList<>()
            ).add(connection);
            connectionsByProvided.computeIfAbsent(
                connection.declaration().to(),
                ignored -> new ArrayList<>()
            ).add(connection);
            materialized.add(connection);
        }
        connections = List.copyOf(materialized);
        connectionsById = Collections.unmodifiableMap(byId);
        if (connections.size() != declarations.size()
            || connectionsById.size() != declarations.size()
            || connectionsByRequired.size() != declarations.size()) {
            throw new IllegalStateException(
                "Runtime connection materialization is not one-to-one with topology declarations"
            );
        }
        connections.forEach(this::recordLifecycle);
    }

    synchronized void beginStartup() {
        connections.forEach(connection -> requireState(connection, ConnectionState.DECLARED));
        for (RuntimeConnection<?> connection : connections) {
            connection.beginStartup();
            recordLifecycle(connection);
        }
    }

    synchronized List<RuntimeConnectionSnapshot> snapshots() {
        return connections.stream().map(RuntimeConnection::snapshot).toList();
    }

    synchronized RuntimeConnectionSnapshot snapshot(ConnectionId id) {
        return requireConnection(id).snapshot();
    }

    synchronized RuntimeConnection<?> connection(ConnectionId id) {
        return requireConnection(id);
    }

    synchronized boolean contains(ConnectionId id) {
        return connectionsById.containsKey(Objects.requireNonNull(id, "id must not be null"));
    }

    synchronized <T> T resolve(RequiredPort<T> required) {
        Objects.requireNonNull(required, "required must not be null");
        RuntimeConnection<?> connection = connectionsByRequired.get(required);
        if (connection == null) {
            throw new IllegalArgumentException(
                "Required port '" + required.qualifiedName()
                    + "' has no runtime connection in this environment"
            );
        }
        return connection.resolve(required);
    }

    synchronized List<PreparedDirectTarget<?>> prepareDirectTargets(
        Component provider,
        ComponentRuntime<?> runtime
    ) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        List<PreparedDirectTarget<?>> prepared = new ArrayList<>();
        for (RuntimeConnection<?> connection : targeting(provider)) {
            prepared.add(prepareDirectTarget(connection, runtime));
        }
        return List.copyOf(prepared);
    }

    synchronized void bindDirectTargets(List<PreparedDirectTarget<?>> preparedTargets) {
        Objects.requireNonNull(preparedTargets, "preparedTargets must not be null");
        Set<RuntimeConnection<?>> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PreparedDirectTarget<?> prepared : preparedTargets) {
            Objects.requireNonNull(prepared, "prepared target must not be null");
            RuntimeConnection<?> registered = connectionsById.get(prepared.connection().id());
            if (registered != prepared.connection()) {
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
            prepared.connection().validateCanBindDirectTarget();
        }
        for (PreparedDirectTarget<?> prepared : preparedTargets) {
            bindPrepared(prepared);
        }
        preparedTargets.forEach(prepared -> recordLifecycle(prepared.connection()));
    }

    synchronized void failProviderMaterialization(Component provider, Throwable failure) {
        for (RuntimeConnection<?> connection : targeting(provider)) {
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

    synchronized void beginProviderCleanup(Component provider) {
        for (RuntimeConnection<?> connection : targeting(provider)) {
            if (connection.state() == ConnectionState.RUNNING
                || connection.state() == ConnectionState.STARTING) {
                connection.beginStopping();
                recordLifecycle(connection);
            }
        }
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
                eventLog.connectionCleanupFailure(connection.declaration(), failure);
            }
        }
    }

    synchronized void stopRemaining() {
        for (RuntimeConnection<?> connection : connections) {
            switch (connection.state()) {
                case DECLARED -> {
                    connection.stopBeforeStartup();
                    recordLifecycle(connection);
                }
                case STARTING, RUNNING -> {
                    connection.beginStopping();
                    recordLifecycle(connection);
                    connection.completeStopping();
                    recordLifecycle(connection);
                }
                case STOPPING -> {
                    connection.completeStopping();
                    recordLifecycle(connection);
                }
                case FAILED, STOPPED -> {
                    // Failed connections are already terminal and unavailable.
                }
            }
        }
    }

    private RuntimeConnection<?> requireConnection(ConnectionId id) {
        Objects.requireNonNull(id, "id must not be null");
        RuntimeConnection<?> connection = connectionsById.get(id);
        if (connection == null) {
            throw new IllegalArgumentException(
                "Connection '" + id + "' is outside the environment"
            );
        }
        return connection;
    }

    private void failMaterialization(RuntimeConnection<?> connection, Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        if (connection.state() == ConnectionState.DECLARED
            || connection.state() == ConnectionState.STARTING
            || connection.state() == ConnectionState.RUNNING
            || connection.state() == ConnectionState.STOPPING) {
            connection.fail();
            recordLifecycle(connection);
            eventLog.connectionMaterializationFailure(connection.declaration(), failure);
        }
    }

    private List<RuntimeConnection<?>> targeting(Component provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return connectionsByProvider.getOrDefault(provider, List.of());
    }

    private List<RuntimeConnection<?>> targeting(ProvidedPort<?> provided) {
        return connectionsByProvided.getOrDefault(provided, List.of());
    }

    private void recordLifecycle(RuntimeConnection<?> connection) {
        eventLog.connectionLifecycle(
            connection.declaration(),
            connection.descriptor(),
            connection.state(),
            connection.routingMode()
        );
    }

    private static RuntimeConnection<?> materialize(ConnectionRef declaration) {
        return switch (declaration) {
            case Connection<?> connection -> materializeTyped(connection);
        };
    }

    private static <C> RuntimeConnection<C> materializeTyped(Connection<C> declaration) {
        return new RuntimeConnection<>(declaration);
    }

    private static PreparedDirectTarget<?> prepareDirectTarget(
        RuntimeConnection<?> connection,
        ComponentRuntime<?> runtime
    ) {
        return prepareTyped(connection, runtime);
    }

    private static <C> PreparedDirectTarget<C> prepareTyped(
        RuntimeConnection<C> connection,
        ComponentRuntime<?> runtime
    ) {
        connection.validateCanBindDirectTarget();
        EndpointBinding<C> target = runtime.binding(connection.declaration().to());
        return new PreparedDirectTarget<>(connection, target);
    }

    private static void bindPrepared(PreparedDirectTarget<?> prepared) {
        bindTyped(prepared);
    }

    private static <C> void bindTyped(PreparedDirectTarget<C> prepared) {
        prepared.connection().bindDirectTarget(prepared.target());
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

    record PreparedDirectTarget<C>(
        RuntimeConnection<C> connection,
        EndpointBinding<C> target
    ) {
        PreparedDirectTarget {
            connection = Objects.requireNonNull(connection, "connection must not be null");
            target = Objects.requireNonNull(target, "target must not be null");
        }
    }
}
