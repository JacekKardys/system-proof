package io.github.jacekkardys.systemproof.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated immutable declaration of components and their communication. */
public final class EnvironmentTopology {
    private final List<AbstractComponent<?, ?>> components;
    private final List<ConnectionRef> connections;
    private final Map<RequiredPort<?>, ConnectionRef> connectionsByRequired;
    private final Map<ConnectionId, ConnectionRef> connectionsById;

    EnvironmentTopology(
        List<AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        this.components = List.copyOf(components);
        this.connections = List.copyOf(connections);
        connectionsByRequired = TopologyValidator.validate(this.components, this.connections);
        Map<ConnectionId, ConnectionRef> byId = new LinkedHashMap<>();
        this.connections.forEach(connection -> byId.put(connection.id(), connection));
        connectionsById = Collections.unmodifiableMap(byId);
    }

    public List<Component> components() {
        return List.copyOf(components);
    }

    public List<ConnectionRef> connections() {
        return connections;
    }

    public boolean contains(Component component) {
        return components.stream().anyMatch(candidate -> candidate == component);
    }

    public ConnectionRef connectionFrom(RequiredPort<?> port) {
        ConnectionRef connection = connectionsByRequired.get(port);
        if (connection == null) {
            throw new IllegalArgumentException(
                Connection.describePort("required", port) + " is not connected"
            );
        }
        return connection;
    }

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

    /** Concrete declarations consumed by an internal runtime. */
    public List<AbstractComponent<?, ?>> componentDefinitions() {
        return components;
    }
}
