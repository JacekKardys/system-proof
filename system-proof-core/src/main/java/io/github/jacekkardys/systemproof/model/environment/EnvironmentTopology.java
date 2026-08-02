package io.github.jacekkardys.systemproof.model.environment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/**
 * Immutable snapshot of component and connection declarations used by an environment at runtime.
 *
 * <p>It contains read indexes only. Structural validation and assembly belong to the
 * construction layer.</p>
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Value
public class EnvironmentTopology {

    List<AbstractComponent<?, ?>> runtimeComponents;
    List<Component> components;
    List<ConnectionRef> connections;

    @Getter(AccessLevel.NONE)
    Map<RequiredPort<?>, ConnectionRef> connectionsByRequired;

    @Getter(AccessLevel.NONE)
    Map<ConnectionId, ConnectionRef> connectionsById;

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
        Objects.requireNonNull(components, "components must not be null");
        Objects.requireNonNull(connections, "connections must not be null");

        List<AbstractComponent<?, ?>> runtimeComponents = List.copyOf(components);
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
}