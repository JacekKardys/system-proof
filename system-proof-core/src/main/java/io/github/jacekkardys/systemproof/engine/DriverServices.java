package io.github.jacekkardys.systemproof.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Driver-facing typed bindings, diagnostics, and environment-scoped shared resources. */
final class DriverServices implements DriverContext {
    private final RuntimeBindings bindings;
    private final Predicate<Component> contains;
    private final Function<Component, ComponentState> componentState;
    private final EnvironmentEventLog eventLog;
    private final IdentityHashMap<DriverResourceKey<?>, AutoCloseable> sharedResources =
        new IdentityHashMap<>();
    private final List<SharedResource> sharedResourceOrder = new ArrayList<>();

    DriverServices(
        RuntimeBindings bindings,
        Predicate<Component> contains,
        Function<Component, ComponentState> componentState,
        EnvironmentEventLog eventLog
    ) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.contains = Objects.requireNonNull(contains, "contains must not be null");
        this.componentState = Objects.requireNonNull(componentState, "componentState must not be null");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog must not be null");
    }

    @Override
    public <T> T resolve(RequiredPort<T> required) {
        return bindings.resolve(required);
    }

    @Override
    public synchronized <R extends AutoCloseable> R sharedResource(
        DriverResourceKey<R> key,
        Supplier<? extends R> factory
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        AutoCloseable existing = sharedResources.get(key);
        if (existing != null) {
            return key.cast(existing);
        }
        R resource = Objects.requireNonNull(factory.get(), "shared resource factory returned null");
        sharedResources.put(key, resource);
        sharedResourceOrder.add(new SharedResource(key.name(), resource));
        return resource;
    }

    @Override
    public void log(Component component, LogLevel level, String message) {
        requireContained(component);
        eventLog.component(component, level, message);
    }

    @Override
    public String componentEvents(Component component) {
        requireContained(component);
        return eventLog.componentSnapshot(component.id());
    }

    @Override
    public ComponentState state(Component component) {
        requireContained(component);
        return componentState.apply(component);
    }

    synchronized Throwable closeSharedResources() {
        Throwable firstFailure = null;
        List<SharedResource> reverse = new ArrayList<>(sharedResourceOrder);
        Collections.reverse(reverse);
        for (SharedResource resource : reverse) {
            try {
                resource.value().close();
            } catch (Exception | Error failure) {
                eventLog.driverResourceCleanupFailure(resource.name(), failure);
                firstFailure = EnvironmentRuntime.accumulate(firstFailure, failure);
            }
        }
        sharedResources.clear();
        sharedResourceOrder.clear();
        return firstFailure;
    }

    private void requireContained(Component component) {
        if (!contains.test(component)) {
            throw new IllegalArgumentException(
                "Component '" + component.id() + "' is outside the environment"
            );
        }
    }

    private record SharedResource(String name, AutoCloseable value) {}
}
