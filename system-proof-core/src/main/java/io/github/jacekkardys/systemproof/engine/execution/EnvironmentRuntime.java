package io.github.jacekkardys.systemproof.engine.execution;

import java.util.List;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.component.ComponentState;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.runtime.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.proof.ProofSubjects;

/** Thread-safe public facade over one environment execution. */
public final class EnvironmentRuntime {
    private final EnvironmentExecution execution;
    private final ComponentRuntimeSupervisor components;
    private final EnvironmentInspector inspector;

    private EnvironmentRuntime(EnvironmentRuntimeFactory.Assembly assembly) {
        this.execution = assembly.execution();
        this.components = assembly.components();
        this.inspector = assembly.inspector();
    }

    public static EnvironmentRuntime of(
        EnvironmentTopology topology,
        EnvironmentLogging logging
    ) {
        return new EnvironmentRuntime(EnvironmentRuntimeFactory.assemble(topology, logging));
    }

    public static EnvironmentRuntime of(
        EnvironmentTopology topology,
        EnvironmentLogging logging,
        ConnectionRouting routing
    ) {
        return new EnvironmentRuntime(
            EnvironmentRuntimeFactory.assemble(topology, logging, routing)
        );
    }

    public synchronized void start() {
        execution.start();
    }

    public synchronized EnvironmentState state() {
        return execution.state();
    }

    public synchronized ComponentState componentState(Component component) {
        return components.state(component);
    }

    public synchronized <C extends RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        return components.operations(component);
    }

    public synchronized EnvironmentDiagnostics diagnostics() {
        return inspector.diagnostics();
    }

    public synchronized ScenarioJournalSnapshot journalSnapshot() {
        return inspector.journalSnapshot();
    }

    public synchronized ProofSubjects proofSubjects() {
        return inspector.proofSubjects();
    }

    public synchronized List<RuntimeConnectionSnapshot> connectionSnapshots() {
        return inspector.connectionSnapshots();
    }

    public synchronized RuntimeConnectionSnapshot connectionSnapshot(ConnectionId id) {
        return inspector.connectionSnapshot(id);
    }

    public synchronized void close() {
        execution.close();
    }
}
