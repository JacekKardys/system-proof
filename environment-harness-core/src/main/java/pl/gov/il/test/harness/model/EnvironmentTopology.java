package pl.gov.il.test.harness.model;

import java.util.List;
import java.util.Map;

/** Validated immutable declaration of components and their communication. */
public final class EnvironmentTopology {
    private final List<AbstractComponent<?, ?>> components;
    private final List<ConnectionRef> connections;
    private final Map<RequiredPort<?>, ConnectionRef> connectionsByRequired;

    EnvironmentTopology(
        List<AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        this.components = List.copyOf(components);
        this.connections = List.copyOf(connections);
        connectionsByRequired = TopologyValidator.validate(this.components, this.connections);
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
                "Required port '" + port.qualifiedName() + "' is not connected"
            );
        }
        return connection;
    }

    /** Concrete declarations consumed by an internal runtime. */
    public List<AbstractComponent<?, ?>> componentDefinitions() {
        return components;
    }
}
