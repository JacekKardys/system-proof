package io.github.jacekkardys.systemproof.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentLifecycleException;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.EnvironmentState;
import io.github.jacekkardys.systemproof.model.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.RuntimeConnectionSnapshot;

/** Owns one environment execution: start, readiness, operations, diagnostics, stop, and cleanup. */
public final class EnvironmentRuntime {
    private final List<AbstractComponent<?, ?>> components;
    private final List<AbstractComponent<?, ?>> startOrder;
    private final RuntimeConnectionRegistry connections;
    private final RuntimeBindings bindings;
    private final Map<Component, ComponentState> componentStates = new IdentityHashMap<>();
    private final List<AbstractComponent<?, ?>> started = new ArrayList<>();
    private final ScenarioJournal journal;
    private final EnvironmentEventLog eventLog;
    private final RuntimeDiagnostics diagnostics;
    private EnvironmentState state = EnvironmentState.DECLARED;
    private DriverServices driverServices;

    public EnvironmentRuntime(
        EnvironmentTopology topology,
        EnvironmentLogging logging
    ) {
        topology = Objects.requireNonNull(topology, "topology must not be null");
        components = topology.componentDefinitions();
        startOrder = ComponentStartPlan.order(components, topology::connectionFrom);
        logging = Objects.requireNonNull(logging, "logging must not be null");
        components.forEach(component -> componentStates.put(component, ComponentState.DECLARED));
        journal = new ScenarioJournal();
        eventLog = new EnvironmentEventLog(journal, logging);
        connections = new RuntimeConnectionRegistry(topology.connections(), eventLog);
        bindings = new RuntimeBindings(connections);
        diagnostics = new RuntimeDiagnostics(journal, eventLog);
    }

    public synchronized void start() {
        if (state != EnvironmentState.DECLARED) {
            throw new IllegalStateException("Environment cannot start from state " + state);
        }
        transitionEnvironment(EnvironmentState.STARTING);
        driverServices = new DriverServices(
            bindings,
            this::contains,
            this::componentState,
            eventLog
        );
        try {
            connections.beginStartup();
            for (AbstractComponent<?, ?> component : startOrder) {
                startComponent(component);
            }
            transitionEnvironment(EnvironmentState.RUNNING);
        } catch (RuntimeException | Error failure) {
            transitionEnvironment(EnvironmentState.FAILED);
            eventLog.environmentStartupFailure(failure);
            Throwable cleanupFailure = cleanup();
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            transitionEnvironment(EnvironmentState.STOPPED);
            EnvironmentDiagnostics captured = diagnostics();
            throw new EnvironmentStartException(failure, captured);
        }
    }

    public synchronized EnvironmentState state() {
        return state;
    }

    public synchronized ComponentState componentState(Component component) {
        ComponentState componentState = componentStates.get(component);
        if (componentState == null) {
            throw new IllegalArgumentException(
                "Component '" + component.id() + "' is outside the environment"
            );
        }
        return componentState;
    }

    public synchronized <C extends RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        ComponentState componentState = componentState(component);
        if (componentState != ComponentState.RUNNING) {
            throw new ComponentLifecycleException(
                component.id(),
                component.type(),
                componentState,
                ComponentState.RUNNING
            );
        }
        return bindings.operations(component);
    }

    public synchronized EnvironmentDiagnostics diagnostics() {
        return diagnostics.capture(
            state,
            components,
            this::componentState,
            connections.snapshots()
        );
    }

    public ScenarioJournalSnapshot journalSnapshot() {
        return journal.snapshot();
    }

    public synchronized List<RuntimeConnectionSnapshot> connectionSnapshots() {
        return connections.snapshots();
    }

    public synchronized RuntimeConnectionSnapshot connectionSnapshot(ConnectionId id) {
        return connections.snapshot(id);
    }

    public synchronized void close() {
        if (state == EnvironmentState.STOPPED) {
            return;
        }
        if (state == EnvironmentState.DECLARED) {
            connections.stopRemaining();
            transitionEnvironment(EnvironmentState.STOPPED);
            return;
        }
        if (state != EnvironmentState.RUNNING) {
            throw new IllegalStateException("Environment cannot close from state " + state);
        }
        transitionEnvironment(EnvironmentState.STOPPING);
        Throwable failure = cleanup();
        if (failure != null) {
            transitionEnvironment(EnvironmentState.FAILED);
        }
        transitionEnvironment(EnvironmentState.STOPPED);
        if (failure != null) {
            rethrow(failure);
        }
    }

    private <C extends RuntimeConfig, O> void startComponent(
        AbstractComponent<C, O> component
    ) {
        transitionComponent(component, ComponentState.STARTING);
        eventLog.component(
            component,
            LogLevel.DEBUG,
            "Configuration " + component.configuration()
        );
        ComponentRuntime<O> runtime = null;
        try {
            runtime = Objects.requireNonNull(
                component.driver().start(component, driverServices.contextFor(component)),
                "Driver for component '" + component.id() + "' returned null runtime"
            );
            bindings.attach(component, runtime);
            started.add(component);
            diagnostics.add(component, runtime.diagnostics());
            transitionComponent(component, ComponentState.RUNNING);
        } catch (RuntimeException | Error failure) {
            bindings.providerStartFailure(component, failure);
            transitionComponent(component, ComponentState.FAILED);
            eventLog.componentStartupFailure(component, failure);
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (Exception | Error cleanupFailure) {
                    eventLog.componentCleanupFailure(component, cleanupFailure);
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private Throwable cleanup() {
        Throwable firstFailure = null;
        List<AbstractComponent<?, ?>> reverse = new ArrayList<>(started);
        Collections.reverse(reverse);
        for (AbstractComponent<?, ?> component : reverse) {
            transitionComponent(component, ComponentState.STOPPING);
            try {
                bindings.beginDetach(component);
                bindings.runtime(component).close();
                bindings.completeDetach(component);
                transitionComponent(component, ComponentState.STOPPED);
            } catch (Exception | Error failure) {
                bindings.failDetach(component, failure);
                transitionComponent(component, ComponentState.FAILED);
                eventLog.componentCleanupFailure(component, failure);
                firstFailure = accumulate(firstFailure, failure);
            } finally {
                bindings.detachRuntime(component);
            }
        }
        started.clear();
        connections.stopRemaining();
        if (driverServices != null) {
            firstFailure = accumulate(firstFailure, driverServices.closeSharedResources());
        }
        return firstFailure;
    }

    private void transitionEnvironment(EnvironmentState next) {
        state = next;
        eventLog.environmentLifecycle(next);
    }

    private void transitionComponent(Component component, ComponentState next) {
        componentStates.put(component, next);
        eventLog.componentLifecycle(component, next);
    }

    private boolean contains(Component component) {
        return componentStates.containsKey(component);
    }

    static Throwable accumulate(Throwable first, Throwable next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Environment cleanup failed", failure);
    }
}
