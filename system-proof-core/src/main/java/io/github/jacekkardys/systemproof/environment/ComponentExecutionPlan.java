package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

/** Immutable component membership and startup order for one environment execution. */
final class ComponentExecutionPlan {
    private final List<AbstractComponent<?, ?>> components;
    private final List<AbstractComponent<?, ?>> startOrder;

    ComponentExecutionPlan(
        List<AbstractComponent<?, ?>> components,
        List<AbstractComponent<?, ?>> startOrder
    ) {
        this.components = validatedCopy(components, "components");
        this.startOrder = validatedCopy(startOrder, "startOrder");
        validateCompleteOrder();
    }

    static ComponentExecutionPlan create(
        List<AbstractComponent<?, ?>> components,
        Function<RequiredPort<?>, ConnectionRef> connectionFrom
    ) {
        List<AbstractComponent<?, ?>> declared = validatedCopy(components, "components");
        Objects.requireNonNull(connectionFrom, "connectionFrom must not be null");
        return new ComponentExecutionPlan(
            declared,
            order(declared, connectionFrom)
        );
    }

    List<AbstractComponent<?, ?>> components() {
        return components;
    }

    List<AbstractComponent<?, ?>> startOrder() {
        return startOrder;
    }

    private void validateCompleteOrder() {
        Set<AbstractComponent<?, ?>> declared = identitySet();
        declared.addAll(components);
        for (AbstractComponent<?, ?> component : startOrder) {
            if (!declared.contains(component)) {
                throw new IllegalArgumentException(
                    "startOrder contains component '" + component.id()
                        + "' outside the execution plan"
                );
            }
        }
        if (startOrder.size() != components.size()) {
            throw new IllegalArgumentException(
                "startOrder must contain every component exactly once"
            );
        }
    }

    private static List<AbstractComponent<?, ?>> validatedCopy(
        List<AbstractComponent<?, ?>> source,
        String name
    ) {
        Objects.requireNonNull(source, name + " must not be null");
        Set<AbstractComponent<?, ?>> unique = identitySet();
        List<AbstractComponent<?, ?>> copy = new ArrayList<>(source.size());
        for (AbstractComponent<?, ?> component : source) {
            Objects.requireNonNull(component, name + " must not contain null components");
            if (!unique.add(component)) {
                throw new IllegalArgumentException(
                    name + " must not contain duplicate component '" + component.id() + "'"
                );
            }
            copy.add(component);
        }
        return List.copyOf(copy);
    }

    private static List<AbstractComponent<?, ?>> order(
        List<AbstractComponent<?, ?>> components,
        Function<RequiredPort<?>, ConnectionRef> connectionFrom
    ) {
        List<AbstractComponent<?, ?>> ordered = new ArrayList<>();
        Set<AbstractComponent<?, ?>> visiting = identitySet();
        Set<AbstractComponent<?, ?>> visited = identitySet();
        Map<Component, AbstractComponent<?, ?>> declared = new IdentityHashMap<>();
        components.forEach(component -> declared.put(component, component));
        components.forEach(component ->
            visit(component, connectionFrom, declared, visiting, visited, ordered)
        );
        return List.copyOf(ordered);
    }

    private static void visit(
        AbstractComponent<?, ?> component,
        Function<RequiredPort<?>, ConnectionRef> connectionFrom,
        Map<Component, AbstractComponent<?, ?>> declared,
        Set<AbstractComponent<?, ?>> visiting,
        Set<AbstractComponent<?, ?>> visited,
        List<AbstractComponent<?, ?>> ordered
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
            .filter(port -> port instanceof RequiredPort<?> required && required.requiredAtStartup())
            .map(port -> (RequiredPort<?>) port)
            .map(connectionFrom)
            .map(connection -> connection.to().owner())
            .map(owner -> requireDeclaredDependency(component, owner, declared))
            .forEach(dependency ->
                visit(dependency, connectionFrom, declared, visiting, visited, ordered)
            );
        visiting.remove(component);
        visited.add(component);
        ordered.add(component);
    }

    private static AbstractComponent<?, ?> requireDeclaredDependency(
        Component consumer,
        Component dependency,
        Map<Component, AbstractComponent<?, ?>> declared
    ) {
        AbstractComponent<?, ?> declaredDependency = declared.get(dependency);
        if (declaredDependency == null) {
            throw new IllegalArgumentException(
                "Startup dependency '" + dependency.id() + "' of component '"
                    + consumer.id() + "' is outside the execution plan"
            );
        }
        return declaredDependency;
    }

    private static Set<AbstractComponent<?, ?>> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
