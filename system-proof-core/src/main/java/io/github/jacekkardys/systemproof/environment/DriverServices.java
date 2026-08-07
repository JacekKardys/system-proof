package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.driver.JournalContributions;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

/** Driver-facing typed bindings, diagnostics, and environment-scoped shared resources. */
final class DriverServices {
    private final RuntimeBindings bindings;
    private final Predicate<Component> contains;
    private final Function<Component, ComponentState> componentState;
    private final RuntimeDiagnostics diagnostics;
    private final EnvironmentEventPublisher events;
    private final SharedDriverResources sharedResources;

    DriverServices(
        RuntimeBindings bindings,
        Predicate<Component> contains,
        Function<Component, ComponentState> componentState,
        RuntimeDiagnostics diagnostics,
        EnvironmentEventPublisher events
    ) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.contains = Objects.requireNonNull(contains, "contains must not be null");
        this.componentState = Objects.requireNonNull(componentState, "componentState must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        sharedResources = new SharedDriverResources(events);
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

    <R extends AutoCloseable> R sharedResource(
        DriverResourceKey<R> key,
        Supplier<? extends R> factory
    ) {
        return sharedResources.getOrCreate(key, factory);
    }

    private void log(
        Component component,
        LogLevel level,
        RedactedDiagnosticText message
    ) {
        requireContained(component);
        events.component(component, level, message);
    }

    private String componentEvents(Component component) {
        requireContained(component);
        return diagnostics.componentEvents(component);
    }

    private ComponentState state(Component component) {
        requireContained(component);
        return componentState.apply(component);
    }

    DriverContext contextFor(Component component) {
        requireContained(component);
        return new ScopedDriverContext(component);
    }

    Throwable closeSharedResources() {
        return sharedResources.close();
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
        public void log(
            Component component,
            LogLevel level,
            RedactedDiagnosticText message
        ) {
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
            events.checkpoint(owner, checkpointId, kind, stage);
        }

        @Override
        public void recordDisruption(
            DisruptionId disruptionId,
            DisruptionLifecycleEvent.Stage stage
        ) {
            events.disruption(owner, disruptionId, stage);
        }
    }
}
