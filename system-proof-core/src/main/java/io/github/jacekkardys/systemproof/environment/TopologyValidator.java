package io.github.jacekkardys.systemproof.environment;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.topology.PortDirection;
import io.github.jacekkardys.systemproof.topology.PortRef;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

/** Structural validation performed while an environment topology is constructed. */
final class TopologyValidator {
    private TopologyValidator() {}

    static void validate(List<AbstractComponent<?, ?>> components, List<ConnectionRef> connections) {
        synchronized (ComponentInitializer.class) {
            validateStableDeclarations(components, connections);
            components.forEach(ComponentInitializer::freezePortDeclarations);
        }
    }

    private static void validateStableDeclarations(
        List<AbstractComponent<?, ?>> components,
        List<ConnectionRef> connections
    ) {
        if (components.isEmpty()) {
            throw new IllegalArgumentException("Environment must contain at least one component");
        }
        Set<ComponentId> ids = new HashSet<>();
        Set<Component> registeredComponents = identitySet();
        Set<PortRef> registeredPorts = identitySet();
        for (AbstractComponent<?, ?> component : components) {
            Objects.requireNonNull(component, "component must not be null");
            ComponentInitializer.validateInitialized(component);
            if (!ids.add(component.id())) {
                throw new IllegalArgumentException("Duplicate component ID '" + component.id() + "'");
            }
            registeredComponents.add(component);
            registeredPorts.addAll(component.ports());
        }

        IdentityHashMap<RequiredPort<?>, ConnectionRef> connected = new IdentityHashMap<>();
        Map<ConnectionId, ConnectionRef> connectionsById = new HashMap<>();
        for (ConnectionRef connection : connections) {
            Objects.requireNonNull(connection, "connection must not be null");
            PortRef from = Objects.requireNonNull(
                connection.from(),
                "connection source port must not be null"
            );
            PortRef to = Objects.requireNonNull(
                connection.to(),
                "connection target port must not be null"
            );
            requireRegistered(from, registeredComponents, registeredPorts);
            requireRegistered(to, registeredComponents, registeredPorts);
            if (from.direction() != PortDirection.REQUIRED
                || to.direction() != PortDirection.PROVIDED) {
                throw new IllegalArgumentException("Connection '" + connection.id()
                    + "' must be REQUIRED -> PROVIDED");
            }
            RequiredPort<?> required = (RequiredPort<?>) from;
            ConnectionId expectedId = ConnectionId.between(required, (ProvidedPort<?>) to);
            ConnectionId actualId = Objects.requireNonNull(
                connection.id(),
                "connection id must not be null for " + describe(connection)
            );
            if (!actualId.equals(expectedId)) {
                throw new IllegalArgumentException("Connection ID mismatch: declared='" + actualId
                    + "', expected='" + expectedId + "' for " + describe(connection));
            }
            ConnectionFactory.validateCompatibility(from, to);

            ConnectionRef duplicate = connectionsById.putIfAbsent(actualId, connection);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate connection '" + actualId + "': existing "
                    + describe(duplicate) + "; conflicting " + describe(connection));
            }
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

    private static void requireRegistered(
        PortRef port,
        Set<Component> registeredComponents,
        Set<PortRef> registeredPorts
    ) {
        String description = describePort(
            port.direction() == PortDirection.REQUIRED ? "required" : "provided",
            port
        );
        if (!registeredComponents.contains(port.owner())) {
            throw new IllegalArgumentException(description
                + " belongs to a component outside this environment");
        }
        if (!registeredPorts.contains(port)
            || port.owner().ports().stream().noneMatch(candidate -> candidate == port)) {
            throw new IllegalArgumentException(description
                + " is not the exact port instance registered by component '"
                + port.owner().id() + "'");
        }
    }

    private static String describe(ConnectionRef connection) {
        return describePort("required", connection.from()) + " to " + describePort("provided", connection.to());
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
