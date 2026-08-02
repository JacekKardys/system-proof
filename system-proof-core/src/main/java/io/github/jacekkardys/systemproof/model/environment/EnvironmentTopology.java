package io.github.jacekkardys.systemproof.model.environment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/**
 * Immutable snapshot of component and connection declarations used by an environment at runtime.
 * It contains read indexes only; structural validation and assembly belong to the construction layer.
 */
public final class EnvironmentTopology {
    private final List<AbstractComponent<?, ?>> runtimeComponents;
    private final List<Component> components;
    private final List<ConnectionRef> connections;
    private final Map<RequiredPort<?>, ConnectionRef> connectionsByRequired;
    private final Map<ConnectionId, ConnectionRef> connectionsById;

    /**
     * Captures an already validated topology.
     *
     * <p>The normal entry point is {@code EnvironmentBuilder}. This low-level constructor does not
     * repeat structural validation owned by the construction layer.</p>
     *
     * <p>Every component must extend {@link AbstractComponent} because only that typed model carries
     * the driver contract required by environment execution.</p>
     *
     * @param components runtime-manageable components in declaration order
     * @param connections logical connections in declaration order
     */
    public EnvironmentTopology(
        List<? extends AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        runtimeComponents = copyRuntimeComponents(components);
        this.components = List.copyOf(runtimeComponents);
        this.connections = List.copyOf(connections);

        Map<RequiredPort<?>, ConnectionRef> byRequired = new IdentityHashMap<>();
        Map<ConnectionId, ConnectionRef> byId = new LinkedHashMap<>();
        this.connections.forEach(connection -> {
            byRequired.put((RequiredPort<?>) connection.from(), connection);
            byId.put(connection.id(), connection);
        });
        connectionsByRequired = Collections.unmodifiableMap(byRequired);
        connectionsById = Collections.unmodifiableMap(byId);
    }

    /** Returns the components in declaration order. */
    public List<Component> components() {
        return components;
    }

    /** Returns the typed component definitions consumed by environment execution. */
    public List<AbstractComponent<?, ?>> runtimeComponents() {
        return runtimeComponents;
    }

    /** Returns the logical connections in declaration order. */
    public List<ConnectionRef> connections() {
        return connections;
    }

    /** Returns whether this topology owns the exact supplied component instance. */
    public boolean contains(Component component) {
        return components.stream().anyMatch(candidate -> candidate == component);
    }

    /** Returns the connection originating at the supplied required port. */
    public ConnectionRef connectionFrom(RequiredPort<?> port) {
        ConnectionRef connection = connectionsByRequired.get(port);
        if (connection == null) {
            throw new IllegalArgumentException("Required port '" + port.qualifiedName() + "' is not connected");
        }
        return connection;
    }

    /** Returns the connection with the supplied deterministic identity. */
    public ConnectionRef connection(ConnectionId id) {
        Objects.requireNonNull(id, "id must not be null");
        ConnectionRef connection = connectionsById.get(id);
        if (connection == null) {
            throw new IllegalArgumentException("Connection '" + id + "' is outside the environment");
        }
        return connection;
    }

    private static List<AbstractComponent<?, ?>> copyRuntimeComponents(
        List<? extends AbstractComponent<?, ?>> components
    ) {
        Objects.requireNonNull(components, "components must not be null");
        for (Object component : components) {
            Objects.requireNonNull(component, "components must not contain null");
            if (!AbstractComponent.class.isInstance(component)) {
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
