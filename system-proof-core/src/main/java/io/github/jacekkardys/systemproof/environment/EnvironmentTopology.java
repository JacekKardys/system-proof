package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.experimental.Accessors;

import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

/**
 * Structurally validated immutable snapshot of component and connection declarations used by an
 * environment at runtime.
 *
 * <p>The public factory validates the exact defensive snapshots retained by the topology before
 * building its read indexes. Every supported instance is therefore valid before it can reach
 * environment execution.</p>
 */
@Accessors(fluent = true)
@Value
public final class EnvironmentTopology {

    @Getter(AccessLevel.PACKAGE)
    List<AbstractComponent<?, ?>> runtimeComponents;
    List<Component> components;
    List<ConnectionRef> connections;

    @Getter(AccessLevel.NONE)
    Map<RequiredPort<?>, ConnectionRef> connectionsByRequired;

    @Getter(AccessLevel.NONE)
    Map<ConnectionId, ConnectionRef> connectionsById;

    private EnvironmentTopology(
        List<AbstractComponent<?, ?>> runtimeComponents,
        List<Component> components,
        List<ConnectionRef> connections,
        Map<RequiredPort<?>, ConnectionRef> connectionsByRequired,
        Map<ConnectionId, ConnectionRef> connectionsById
    ) {
        this.runtimeComponents = runtimeComponents;
        this.components = components;
        this.connections = connections;
        this.connectionsByRequired = connectionsByRequired;
        this.connectionsById = connectionsById;
    }

    /**
     * Defensively snapshots and structurally validates a topology.
     *
     * @param components runtime-manageable components in declaration order
     * @param connections logical connections in declaration order
     * @return structurally validated immutable topology snapshot
     * @throws NullPointerException if either list or any list element is {@code null}
     * @throws IllegalArgumentException if the topology violates a structural invariant
     */
    public static EnvironmentTopology of(
        List<? extends AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        List<AbstractComponent<?, ?>> runtimeComponents = copyRuntimeComponents(components);
        List<ConnectionRef> connectionSnapshot = copyConnections(connections);

        TopologyValidator.validate(runtimeComponents, connectionSnapshot);

        Map<RequiredPort<?>, ConnectionRef> byRequired = new IdentityHashMap<>();
        Map<ConnectionId, ConnectionRef> byId = new LinkedHashMap<>();

        for (ConnectionRef connection : connectionSnapshot) {
            byRequired.put((RequiredPort<?>) connection.from(), connection);
            byId.put(connection.id(), connection);
        }

        return new EnvironmentTopology(
            runtimeComponents,
            List.copyOf(runtimeComponents),
            connectionSnapshot,
            Collections.unmodifiableMap(byRequired),
            Collections.unmodifiableMap(byId)
        );
    }

    /** Returns whether this topology owns the exact supplied component instance. */
    public boolean contains(Component component) {
        return components.stream().anyMatch(candidate -> candidate == component);
    }

    /** Returns the connection originating at the supplied required port. */
    public ConnectionRef connectionFrom(RequiredPort<?> port) {
        Objects.requireNonNull(port, "port must not be null");

        ConnectionRef connection = connectionsByRequired.get(port);
        if (connection == null) {
            throw new IllegalArgumentException(
                "Required port '" + port.qualifiedName() + "' is not connected"
            );
        }
        return connection;
    }

    /** Returns the connection with the supplied deterministic identity. */
    public ConnectionRef connection(ConnectionId id) {
        Objects.requireNonNull(id, "id must not be null");

        ConnectionRef connection = connectionsById.get(id);
        if (connection == null) {
            throw new IllegalArgumentException(
                "Connection '" + id + "' is outside the environment"
            );
        }
        return connection;
    }

    private static List<AbstractComponent<?, ?>> copyRuntimeComponents(
        List<? extends AbstractComponent<?, ?>> components
    ) {
        Objects.requireNonNull(components, "components must not be null");
        List<?> componentSnapshot = new ArrayList<>(components);
        List<AbstractComponent<?, ?>> runtimeComponents = new ArrayList<>(componentSnapshot.size());
        for (Object component : componentSnapshot) {
            Objects.requireNonNull(component, "components must not contain null");
            if (!(component instanceof AbstractComponent<?, ?>)) {
                throw new IllegalArgumentException(
                    "EnvironmentTopology accepts only runtime components extending "
                        + AbstractComponent.class.getName() + "; unsupported component type='"
                        + component.getClass().getName() + "'"
                );
            }
            runtimeComponents.add((AbstractComponent<?, ?>) component);
        }
        return List.copyOf(runtimeComponents);
    }

    private static List<ConnectionRef> copyConnections(List<ConnectionRef> connections) {
        Objects.requireNonNull(connections, "connections must not be null");
        List<ConnectionRef> connectionSnapshot = new ArrayList<>(connections);
        for (ConnectionRef connection : connectionSnapshot) {
            Objects.requireNonNull(connection, "connections must not contain null");
        }
        return List.copyOf(connectionSnapshot);
    }
}
