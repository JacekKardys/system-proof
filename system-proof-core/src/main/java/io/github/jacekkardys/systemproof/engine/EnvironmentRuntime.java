package io.github.jacekkardys.systemproof.engine;

import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.component.ComponentState;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.logging.LogLevel;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.runtime.RuntimeConnectionSnapshot;

/** Owns one environment execution: start, readiness, operations, diagnostics, stop, and cleanup. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class EnvironmentRuntime {
    private final List<AbstractComponent<?, ?>> components;
    private final List<AbstractComponent<?, ?>> startOrder;
    private final RuntimeConnectionRegistry connections;
    private final RuntimeBindings bindings;
    private final ScenarioJournal journal;
    private final EnvironmentEventLog eventLog;
    private final ProofSubjectRegistry proofSubjects;
    private final RuntimeDiagnostics diagnostics;
    private final EnvironmentLifecycle lifecycle;
    private DriverServices driverServices;

    public static EnvironmentRuntime of(EnvironmentTopology topology, EnvironmentLogging logging) {
        return EnvironmentRuntimeFactory.create(topology, logging);
    }

    public static EnvironmentRuntime of(EnvironmentTopology topology, EnvironmentLogging logging, ConnectionRouting routing) {
        return EnvironmentRuntimeFactory.create(topology, logging, routing);
    }

    public synchronized void start() {
        lifecycle.beginStart();
        driverServices = new DriverServices(
            bindings,
            lifecycle::contains,
            lifecycle::componentState,
            eventLog
        );
        try {
            connections.beginStartup();
            for (AbstractComponent<?, ?> component : startOrder) {
                startComponent(component);
            }
            lifecycle.markReady();
        } catch (RuntimeException | Error failure) {
            lifecycle.markStartFailed();
            eventLog.environmentStartupFailure(failure);
            Throwable cleanupFailure = cleanup();
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            lifecycle.markStopped();
            EnvironmentDiagnostics captured = diagnostics();
            throw new EnvironmentStartException(failure, captured);
        }
    }

    public synchronized EnvironmentState state() {
        return lifecycle.state();
    }

    public synchronized ComponentState componentState(Component component) {
        return lifecycle.componentState(component);
    }

    public synchronized <C extends RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        ComponentState componentState = componentState(component);
        if (componentState != ComponentState.RUNNING) {
            throw EnvironmentRuntimeFailures.componentNotRunning(component, componentState);
        }
        return bindings.operations(component);
    }

    public synchronized EnvironmentDiagnostics diagnostics() {
        return diagnostics.capture(
            lifecycle.state(),
            components,
            lifecycle::componentState,
            connections.snapshots()
        );
    }

    public ScenarioJournalSnapshot journalSnapshot() {
        return journal.snapshot();
    }

    public ProofSubjects proofSubjects() {
        return proofSubjects;
    }

    public synchronized List<RuntimeConnectionSnapshot> connectionSnapshots() {
        return connections.snapshots();
    }

    public synchronized RuntimeConnectionSnapshot connectionSnapshot(ConnectionId id) {
        return connections.snapshot(id);
    }

    public synchronized void close() {
        switch (lifecycle.beginClose()) {
            case ALREADY_STOPPED -> {
                return;
            }
            case STOP_DECLARED -> {
                connections.stopRemaining();
                proofSubjects.completeExecution();
                lifecycle.markStopped();
            }
            case CLEAN_UP_RUNNING -> {
                Throwable failure = cleanup();
                if (failure != null) {
                    lifecycle.markCleanupFailed();
                }
                lifecycle.markStopped();
                if (failure != null) {
                    EnvironmentRuntimeFailures.rethrowCleanupFailure(failure);
                }
            }
        }
    }

    private <C extends RuntimeConfig, O> void startComponent(
        AbstractComponent<C, O> component
    ) {
        lifecycle.beginComponentStart(component);
        eventLog.component(
            component,
            LogLevel.DEBUG,
            "Configuration " + component.configuration()
        );
        ComponentRuntime<O> runtime = null;
        boolean attached = false;
        try {
            runtime = Objects.requireNonNull(
                component.driver().start(component, driverServices.contextFor(component)),
                "Driver for component '" + component.id() + "' returned null runtime"
            );
            bindings.attach(component, runtime);
            attached = true;
            diagnostics.add(component, runtime.diagnostics());
            lifecycle.componentStarted(component);
        } catch (RuntimeException | Error failure) {
            bindings.providerStartFailure(component, failure);
            lifecycle.componentStartFailed(component);
            eventLog.componentStartupFailure(component, failure);
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (Exception | Error cleanupFailure) {
                    eventLog.componentCleanupFailure(component, cleanupFailure);
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (attached) {
                bindings.detachRuntime(component);
            }
            throw failure;
        }
    }

    private Throwable cleanup() {
        Throwable firstFailure = null;
        for (AbstractComponent<?, ?> component : lifecycle.componentsToStop()) {
            lifecycle.beginComponentStop(component);
            Throwable componentFailure = bindings.beginDetach(component);
            Throwable providerFailure = null;
            try {
                bindings.runtime(component).close();
            } catch (Exception | Error failure) {
                providerFailure = failure;
                componentFailure = EnvironmentRuntimeFailures.accumulate(
                    componentFailure,
                    failure
                );
            }
            if (providerFailure == null) {
                bindings.completeDetach(component);
            } else {
                bindings.failDetach(component, providerFailure);
            }
            if (componentFailure == null) {
                lifecycle.componentStopped(component);
            } else {
                lifecycle.componentCleanupFailed(component);
                eventLog.componentCleanupFailure(component, componentFailure);
                firstFailure = EnvironmentRuntimeFailures.accumulate(
                    firstFailure,
                    componentFailure
                );
            }
            bindings.detachRuntime(component);
        }
        firstFailure = EnvironmentRuntimeFailures.accumulate(
            firstFailure,
            connections.stopRemaining()
        );
        if (driverServices != null) {
            firstFailure = EnvironmentRuntimeFailures.accumulate(
                firstFailure,
                driverServices.closeSharedResources()
            );
        }
        proofSubjects.completeExecution();
        return firstFailure;
    }

}
