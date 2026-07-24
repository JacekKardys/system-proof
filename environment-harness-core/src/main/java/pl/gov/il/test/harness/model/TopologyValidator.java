package pl.gov.il.test.harness.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Structural topology validation independent of lifecycle planning and execution. */
final class TopologyValidator {
    private TopologyValidator() {}

    static Map<RequiredPort<?>, ConnectionRef> validate(
        List<AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        if (components.isEmpty()) {
            throw new IllegalArgumentException("Environment must contain at least one component");
        }
        Set<ComponentId> ids = new HashSet<>();
        Set<PortRef> registeredPorts = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractComponent<?, ?> component : components) {
            Objects.requireNonNull(component, "component must not be null");
            if (!ids.add(component.id())) {
                throw new IllegalArgumentException("Duplicate component ID '" + component.id() + "'");
            }
            registeredPorts.addAll(component.ports());
        }

        IdentityHashMap<RequiredPort<?>, ConnectionRef> connected = new IdentityHashMap<>();
        Set<String> connectionIds = new HashSet<>();
        for (ConnectionRef connection : connections) {
            Objects.requireNonNull(connection, "connection must not be null");
            requireRegistered(connection.from(), registeredPorts);
            requireRegistered(connection.to(), registeredPorts);
            if (connection.from().direction() != PortDirection.REQUIRED
                || connection.to().direction() != PortDirection.PROVIDED) {
                throw new IllegalArgumentException(
                    "Connection '" + connection.id() + "' must be REQUIRED -> PROVIDED"
                );
            }
            if (!connectionIds.add(connection.id())) {
                throw new IllegalArgumentException("Duplicate connection '" + connection.id() + "'");
            }
            RequiredPort<?> required = (RequiredPort<?>) connection.from();
            if (connected.put(required, connection) != null) {
                throw new IllegalArgumentException(
                    "Required port '" + required.qualifiedName() + "' is connected more than once"
                );
            }
        }

        components.stream()
            .flatMap(component -> component.ports().stream())
            .filter(port -> port.direction() == PortDirection.REQUIRED)
            .map(port -> (RequiredPort<?>) port)
            .filter(required -> !connected.containsKey(required))
            .findFirst()
            .ifPresent(required -> {
                throw new IllegalArgumentException(
                    "Required port '" + required.qualifiedName() + "' is not connected"
                );
            });
        return Collections.unmodifiableMap(connected);
    }

    private static void requireRegistered(PortRef port, Set<PortRef> registered) {
        if (!registered.contains(port)
            || port.owner().ports().stream().noneMatch(candidate -> candidate == port)) {
            throw new IllegalArgumentException(
                "Port '" + port.qualifiedName() + "' is not owned by a component in this environment"
            );
        }
    }
}
