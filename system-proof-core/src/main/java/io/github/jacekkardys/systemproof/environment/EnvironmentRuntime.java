package io.github.jacekkardys.systemproof.environment;

import java.util.List;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.proof.ProofSubjects;
import io.github.jacekkardys.systemproof.control.SemanticControls;

/** Thread-safe internal facade over one environment execution. */
final class EnvironmentRuntime {
    private final EnvironmentExecution execution;
    private final ComponentRuntimeSupervisor components;
    private final EnvironmentInspector inspector;
    private final SemanticControls controls;

    private EnvironmentRuntime(EnvironmentRuntimeFactory.Assembly assembly) {
        this.execution = assembly.execution();
        this.components = assembly.components();
        this.inspector = assembly.inspector();
        controls = assembly.controls();
    }

    static EnvironmentRuntime of(
        EnvironmentTopology topology,
        EnvironmentLogging logging
    ) {
        return new EnvironmentRuntime(EnvironmentRuntimeFactory.assemble(topology, logging));
    }

    static EnvironmentRuntime of(
        EnvironmentTopology topology,
        EnvironmentLogging logging,
        ConnectionRouting routing
    ) {
        return new EnvironmentRuntime(
            EnvironmentRuntimeFactory.assemble(topology, logging, routing)
        );
    }

    void start() {
        EnvironmentExecution.StartupFailure failure;
        synchronized (this) {
            failure = execution.start();
        }
        if (failure != null) {
            throw new EnvironmentStartException(
                failure.cause(),
                inspector.renderDiagnostics(failure.diagnostics())
            );
        }
    }

    synchronized EnvironmentState state() {
        return execution.state();
    }

    synchronized ComponentState componentState(Component component) {
        return components.state(component);
    }

    synchronized <C extends RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        return components.operations(component);
    }

    EnvironmentDiagnostics diagnostics() {
        RuntimeDiagnostics.Snapshot snapshot;
        synchronized (this) {
            snapshot = inspector.diagnosticsSnapshot();
        }
        return inspector.renderDiagnostics(snapshot);
    }

    synchronized ScenarioJournalSnapshot journalSnapshot() {
        return inspector.journalSnapshot();
    }

    synchronized ProofSubjects proofSubjects() {
        return inspector.proofSubjects();
    }

    synchronized SemanticControls controls() {
        return controls;
    }

    synchronized List<RuntimeConnectionSnapshot> connectionSnapshots() {
        return inspector.connectionSnapshots();
    }

    synchronized RuntimeConnectionSnapshot connectionSnapshot(ConnectionId id) {
        return inspector.connectionSnapshot(id);
    }

    synchronized void close() {
        execution.close();
    }
}
