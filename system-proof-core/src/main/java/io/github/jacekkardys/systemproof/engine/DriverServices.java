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
import io.github.jacekkardys.systemproof.driver.JournalContributions;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Driver-facing typed bindings, diagnostics, and environment-scoped shared resources. */
final class DriverServices {
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

    <T> T resolve(Component owner, RequiredPort<T> required) {
        requireContained(owner);
        Objects.requireNonNull(required, "required must not be null");
        if (required.owner() != owner) {
            throw new IllegalArgumentException(
                "Driver for component '" + owner.id()
                    + "' cannot resolve required port '" + required.qualifiedName()
                    + "' owned by component '" + required.owner().id() + "'"
            );
        }
        return bindings.resolve(required);
    }

    synchronized <R extends AutoCloseable> R sharedResource(
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

    private void log(Component component, LogLevel level, String message) {
        requireContained(component);
        eventLog.component(component, level, message);
    }

    private String componentEvents(Component component) {
        requireContained(component);
        return eventLog.componentSnapshot(component.id());
    }

    private ComponentState state(Component component) {
        requireContained(component);
        return componentState.apply(component);
    }

    DriverContext contextFor(Component component) {
        requireContained(component);
        return new ScopedDriverContext(component);
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
        Objects.requireNonNull(component, "component must not be null");
        if (!contains.test(component)) {
            throw new IllegalArgumentException(
                "Component '" + component.id() + "' is outside the environment"
            );
        }
    }

    private void requireOwner(Component owner, Component requested) {
        requireContained(requested);
        if (requested != owner) {
            throw new IllegalArgumentException(
                "Driver for component '" + owner.id()
                    + "' cannot write diagnostics for component '" + requested.id() + "'"
            );
        }
    }

    private final class ScopedDriverContext implements DriverContext {
        private final Component owner;
        private final JournalContributions journalContributions;

        private ScopedDriverContext(Component owner) {
            this.owner = owner;
            journalContributions = new ScopedJournalContributions(owner);
        }

        @Override
        public <T> T resolve(RequiredPort<T> required) {
            return DriverServices.this.resolve(owner, required);
        }

        @Override
        public <R extends AutoCloseable> R sharedResource(
            DriverResourceKey<R> key,
            Supplier<? extends R> factory
        ) {
            return DriverServices.this.sharedResource(key, factory);
        }

        @Override
        public void log(Component component, LogLevel level, String message) {
            requireOwner(owner, component);
            DriverServices.this.log(owner, level, message);
        }

        @Override
        public JournalContributions journalContributions() {
            return journalContributions;
        }

        @Override
        public String componentEvents(Component component) {
            return DriverServices.this.componentEvents(component);
        }

        @Override
        public ComponentState state(Component component) {
            return DriverServices.this.state(component);
        }
    }

    private final class ScopedJournalContributions implements JournalContributions {
        private final Component owner;

        private ScopedJournalContributions(Component owner) {
            this.owner = owner;
        }

        @Override
        public void recordCheckpoint(
            CheckpointId checkpointId,
            CheckpointEvent.Kind kind,
            CheckpointEvent.Stage stage
        ) {
            eventLog.checkpoint(owner, checkpointId, kind, stage);
        }

        @Override
        public void recordDisruption(
            DisruptionId disruptionId,
            DisruptionLifecycleEvent.Stage stage
        ) {
            eventLog.disruption(owner, disruptionId, stage);
        }
    }

    private record SharedResource(String name, AutoCloseable value) {}
}
