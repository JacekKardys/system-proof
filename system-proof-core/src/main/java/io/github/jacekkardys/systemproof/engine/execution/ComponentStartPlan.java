package io.github.jacekkardys.systemproof.engine.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/** Orders only explicitly declared startup dependencies, not all communication connections. */
final class ComponentStartPlan {
    private ComponentStartPlan() {}

    static <T extends Component> List<T> order(
        List<T> components,
        Function<RequiredPort<?>, ConnectionRef> connectionFrom
    ) {
        List<T> ordered = new ArrayList<>();
        Set<T> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<T> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Component, T> declared = new IdentityHashMap<>();
        components.forEach(component -> declared.put(component, component));
        components.forEach(component ->
            visit(component, connectionFrom, declared, visiting, visited, ordered)
        );
        return List.copyOf(ordered);
    }

    private static <T extends Component> void visit(
        T component,
        Function<RequiredPort<?>, ConnectionRef> connectionFrom,
        Map<Component, T> declared,
        Set<T> visiting,
        Set<T> visited,
        List<T> ordered
    ) {
        if (visited.contains(component)) {
            return;
        }
        if (!visiting.add(component)) {
            throw new IllegalArgumentException(
                "Environment contains a startup dependency cycle at component '"
                    + component.id() + "'"
            );
        }
        component.ports().stream()
            .filter(port -> port instanceof RequiredPort<?>)
            .map(port -> (RequiredPort<?>) port)
            .filter(RequiredPort::requiredAtStartup)
            .map(connectionFrom)
            .map(connection -> connection.to().owner())
            .map(declared::get)
            .forEach(dependency ->
                visit(dependency, connectionFrom, declared, visiting, visited, ordered)
            );
        visiting.remove(component);
        visited.add(component);
        ordered.add(component);
    }
}
