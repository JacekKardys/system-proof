package io.github.jacekkardys.systemproof.engine.execution;

import java.util.Objects;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;

/** Coordinates the environment lifecycle and cleanup of its execution subsystems. */
final class EnvironmentExecution {
    private final EnvironmentLifecycle lifecycle;
    private final ComponentRuntimeSupervisor components;
    private final RuntimeConnectionRegistry connections;
    private final ProofSubjectRegistry proofSubjects;
    private final EnvironmentEventLog eventLog;
    private final EnvironmentInspector inspector;

    EnvironmentExecution(
        EnvironmentLifecycle lifecycle,
        ComponentRuntimeSupervisor components,
        RuntimeConnectionRegistry connections,
        ProofSubjectRegistry proofSubjects,
        EnvironmentEventLog eventLog,
        EnvironmentInspector inspector
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.components = Objects.requireNonNull(components, "components must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.proofSubjects = Objects.requireNonNull(
            proofSubjects,
            "proofSubjects must not be null"
        );
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog must not be null");
        this.inspector = Objects.requireNonNull(inspector, "inspector must not be null");
    }

    void start() {
        lifecycle.beginStart();
        try {
            connections.beginStartup();
            components.startAll();
            lifecycle.markReady();
        } catch (RuntimeException | Error failure) {
            handleStartupFailure(failure);
        }
    }

    EnvironmentState state() {
        return lifecycle.state();
    }

    void close() {
        switch (lifecycle.beginClose()) {
            case ALREADY_STOPPED -> {
                return;
            }
            case STOP_DECLARED -> closeDeclaredExecution();
            case CLEAN_UP_RUNNING -> closeRunningExecution();
        }
    }

    private void handleStartupFailure(Throwable failure) {
        lifecycle.markStartFailed();
        eventLog.environmentStartupFailure(failure);
        Throwable cleanupFailure = cleanup();
        EnvironmentRuntimeFailures.accumulate(failure, cleanupFailure);
        lifecycle.markStopped();
        EnvironmentDiagnostics captured = inspector.diagnostics();
        throw new EnvironmentStartException(failure, captured);
    }

    private void closeDeclaredExecution() {
        Throwable failure = attempt(connections::stopRemaining);
        failure = EnvironmentRuntimeFailures.accumulate(
            failure,
            completeProofExecution()
        );
        lifecycle.markStopped();
        if (failure != null) {
            EnvironmentRuntimeFailures.rethrowCleanupFailure(failure);
        }
    }

    private void closeRunningExecution() {
        Throwable failure = cleanup();
        if (failure != null) {
            lifecycle.markCleanupFailed();
        }
        lifecycle.markStopped();
        if (failure != null) {
            EnvironmentRuntimeFailures.rethrowCleanupFailure(failure);
        }
    }

    private Throwable cleanup() {
        Throwable firstFailure = attempt(components::stopStartedComponents);
        firstFailure = EnvironmentRuntimeFailures.accumulate(
            firstFailure,
            attempt(connections::stopRemaining)
        );
        firstFailure = EnvironmentRuntimeFailures.accumulate(
            firstFailure,
            attempt(components::closeSharedResources)
        );
        return EnvironmentRuntimeFailures.accumulate(
            firstFailure,
            completeProofExecution()
        );
    }

    private Throwable completeProofExecution() {
        try {
            proofSubjects.completeExecution();
            return null;
        } catch (RuntimeException | Error failure) {
            return failure;
        }
    }

    private static Throwable attempt(CleanupAction action) {
        try {
            return action.run();
        } catch (RuntimeException | Error failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface CleanupAction {
        Throwable run();
    }
}
