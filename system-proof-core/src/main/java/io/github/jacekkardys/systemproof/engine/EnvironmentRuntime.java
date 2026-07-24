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
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.ComponentLifecycleException;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.EnvironmentState;
import io.github.jacekkardys.systemproof.model.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Owns one environment execution: start, readiness, operations, diagnostics, stop, and cleanup. */
public final class EnvironmentRuntime {
    private final List<AbstractComponent<?, ?>> components;
    private final List<AbstractComponent<?, ?>> startOrder;
    private final List<ConnectionRef> connections;
    private final EnvironmentLogging logging;
    private final RuntimeBindings bindings;
    private final Map<Component, ComponentState> componentStates = new IdentityHashMap<>();
    private final List<AbstractComponent<?, ?>> started = new ArrayList<>();
    private EnvironmentState state = EnvironmentState.DECLARED;
    private RuntimeDiagnostics diagnostics;
    private DriverServices driverServices;

    public EnvironmentRuntime(
        EnvironmentTopology topology,
        EnvironmentLogging logging
    ) {
        topology = Objects.requireNonNull(topology, "topology must not be null");
        components = topology.componentDefinitions();
        startOrder = ComponentStartPlan.order(components, topology::connectionFrom);
        connections = topology.connections();
        this.logging = Objects.requireNonNull(logging, "logging must not be null");
        bindings = new RuntimeBindings(topology::connectionFrom);
        components.forEach(component -> componentStates.put(component, ComponentState.DECLARED));
    }

    public synchronized void start() {
        if (state != EnvironmentState.DECLARED) {
            throw new IllegalStateException("Environment cannot start from state " + state);
        }
        state = EnvironmentState.STARTING;
        diagnostics = new RuntimeDiagnostics(new EnvironmentEventLog(logging));
        driverServices = new DriverServices(
            bindings,
            this::contains,
            this::componentState,
            diagnostics.eventLog()
        );
        try {
            diagnostics.eventLog().framework(LogLevel.INFO, "Starting environment");
            connections.forEach(connection -> diagnostics.eventLog().connection(
                connection,
                LogLevel.INFO,
                "Declared " + connection.from().qualifiedName() + " -> " + connection.to().qualifiedName()
            ));
            for (AbstractComponent<?, ?> component : startOrder) {
                startComponent(component);
            }
            state = EnvironmentState.RUNNING;
            diagnostics.eventLog().framework(LogLevel.INFO, "Environment started");
        } catch (RuntimeException | Error failure) {
            state = EnvironmentState.FAILED;
            diagnostics.eventLog().framework(
                LogLevel.ERROR,
                "Environment startup failed: " + failure.getClass().getSimpleName()
                    + messageSuffix(failure)
            );
            Throwable cleanupFailure = cleanup("Environment stopped after startup failure");
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            EnvironmentDiagnostics captured = diagnostics();
            state = EnvironmentState.STOPPED;
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
        if (diagnostics == null) {
            StringBuilder content = new StringBuilder("[STATE] environment=").append(state);
            components.forEach(component -> content.append(System.lineSeparator())
                .append("[STATE] component=").append(component.id())
                .append(" type=").append(component.type())
                .append(" state=").append(componentState(component)));
            return EnvironmentDiagnostics.diagnostics(content.toString());
        }
        return diagnostics.capture(state, components, this::componentState);
    }

    public synchronized void close() {
        if (state == EnvironmentState.STOPPED) {
            return;
        }
        if (state == EnvironmentState.DECLARED) {
            state = EnvironmentState.STOPPED;
            return;
        }
        if (state != EnvironmentState.RUNNING) {
            throw new IllegalStateException("Environment cannot close from state " + state);
        }
        state = EnvironmentState.STOPPING;
        diagnostics.eventLog().framework(LogLevel.INFO, "Stopping environment");
        Throwable failure = cleanup("Environment stopped");
        state = EnvironmentState.STOPPED;
        if (failure != null) {
            rethrow(failure);
        }
    }

    private <C extends RuntimeConfig, O> void startComponent(
        AbstractComponent<C, O> component
    ) {
        componentStates.put(component, ComponentState.STARTING);
        diagnostics.eventLog().component(
            component,
            LogLevel.DEBUG,
            "Configuration " + component.configuration()
        );
        diagnostics.eventLog().component(component, LogLevel.INFO, "Starting component");
        ComponentRuntime<O> runtime = null;
        try {
            runtime = Objects.requireNonNull(
                component.driver().start(component, driverServices),
                "Driver for component '" + component.id() + "' returned null runtime"
            );
            bindings.attach(component, runtime);
            started.add(component);
            diagnostics.add(component, runtime.diagnostics());
            componentStates.put(component, ComponentState.RUNNING);
            diagnostics.eventLog().component(component, LogLevel.INFO, "Component ready");
        } catch (RuntimeException | Error failure) {
            componentStates.put(component, ComponentState.FAILED);
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (Exception | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private Throwable cleanup(String completedMessage) {
        Throwable firstFailure = null;
        List<AbstractComponent<?, ?>> reverse = new ArrayList<>(started);
        Collections.reverse(reverse);
        for (AbstractComponent<?, ?> component : reverse) {
            diagnostics.eventLog().component(component, LogLevel.INFO, "Stopping component");
            componentStates.put(component, ComponentState.STOPPING);
            try {
                bindings.runtime(component).close();
                diagnostics.eventLog().component(component, LogLevel.INFO, "Component stopped");
            } catch (Exception | Error failure) {
                diagnostics.eventLog().component(
                    component,
                    LogLevel.ERROR,
                    "Component cleanup failed: " + failure.getClass().getSimpleName()
                        + messageSuffix(failure)
                );
                firstFailure = accumulate(firstFailure, failure);
            } finally {
                bindings.detach(component);
                componentStates.put(component, ComponentState.STOPPED);
            }
        }
        started.clear();
        if (driverServices != null) {
            firstFailure = accumulate(firstFailure, driverServices.closeSharedResources());
        }
        diagnostics.eventLog().framework(LogLevel.INFO, completedMessage);
        return firstFailure;
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

    static String messageSuffix(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? ""
            : " - " + failure.getMessage();
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
