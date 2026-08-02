package io.github.jacekkardys.systemproof.construction;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.model.topology.PortDirection;
import io.github.jacekkardys.systemproof.model.topology.PortRef;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/** Structural validation performed while an environment topology is constructed. */
final class TopologyValidator {
    private TopologyValidator() {}

    static void validate(List<AbstractComponent<?, ?>> components, List<ConnectionRef> connections) {
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
        Map<ConnectionId, ConnectionRef> connectionsById = new HashMap<>();
        for (ConnectionRef connection : connections) {
            Objects.requireNonNull(connection, "connection must not be null");
            requireRegistered(connection.from(), registeredPorts);
            requireRegistered(connection.to(), registeredPorts);
            if (connection.from().direction() != PortDirection.REQUIRED
                || connection.to().direction() != PortDirection.PROVIDED) {
                throw new IllegalArgumentException("Connection '" + connection.id() + "' must be REQUIRED -> PROVIDED");
            }
            ConnectionRef duplicate = connectionsById.putIfAbsent(connection.id(), connection);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate connection '" + connection.id() + "': existing "
                    + describe(duplicate) + "; conflicting " + describe(connection));
            }
            RequiredPort<?> required = (RequiredPort<?>) connection.from();
            ConnectionRef existing = connected.putIfAbsent(required, connection);
            if (existing != null) {
                throw new IllegalArgumentException(describePort("required", required)
                    + " is connected more than once: existing " + describe(existing)
                    + "; conflicting " + describe(connection));
            }
        }

        components.stream()
            .flatMap(component -> component.ports().stream())
            .filter(port -> port.direction() == PortDirection.REQUIRED)
            .map(port -> (RequiredPort<?>) port)
            .filter(required -> !connected.containsKey(required))
            .findFirst()
            .ifPresent(required -> {
                throw new IllegalArgumentException(describePort("required", required) + " is not connected");
            });
    }

    static String describePort(String role, PortRef port) {
        return role + " port [component='" + port.owner().id()
            + "', localName='" + port.name()
            + "', contractId='" + port.contractId()
            + "', contractType='" + port.contractType().getName()
            + "', interaction='" + port.interaction().id()
            + "', protocol='" + port.protocol().id() + "']";
    }

    private static void requireRegistered(PortRef port, Set<PortRef> registered) {
        if (!registered.contains(port) || port.owner().ports().stream().noneMatch(candidate -> candidate == port)) {
            throw new IllegalArgumentException(describePort(
                port.direction() == PortDirection.REQUIRED ? "required" : "provided", port)
                + " is not owned by a component in this environment");
        }
    }

    private static String describe(ConnectionRef connection) {
        return describePort("required", connection.from()) + " to " + describePort("provided", connection.to());
    }
}
