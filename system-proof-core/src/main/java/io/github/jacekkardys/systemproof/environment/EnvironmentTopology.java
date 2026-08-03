package io.github.jacekkardys.systemproof.environment;

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
 * Immutable snapshot of component and connection declarations used by an environment at runtime.
 *
 * <p>It contains read indexes only. Structural validation and assembly belong to the
 * environment owner.</p>
 */
@Accessors(fluent = true)
@Value
public class EnvironmentTopology {

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
     * Captures an already structurally validated topology.
     *
     * @param components runtime-manageable components in declaration order
     * @param connections logical connections in declaration order
     * @return immutable topology snapshot
     */
    public static EnvironmentTopology of(
        List<? extends AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        List<AbstractComponent<?, ?>> runtimeComponents = copyRuntimeComponents(components);
        Objects.requireNonNull(connections, "connections must not be null");

        List<ConnectionRef> connectionSnapshot = List.copyOf(connections);

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
        for (Object component : components) {
            Objects.requireNonNull(component, "components must not contain null");
            if (!(component instanceof AbstractComponent<?, ?>)) {
                throw new IllegalArgumentException(
                    "EnvironmentTopology accepts only runtime components extending "
                        + AbstractComponent.class.getName() + "; unsupported component type='"
                        + component.getClass().getName() + "'"
                );
            }
        }
        return List.copyOf(components);
    }
}
