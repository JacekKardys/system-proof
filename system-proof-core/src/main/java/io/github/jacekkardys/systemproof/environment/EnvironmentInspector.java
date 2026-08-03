package io.github.jacekkardys.systemproof.environment;

import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.proof.ProofSubjects;

/** Builds read-only views of the current environment execution. */
final class EnvironmentInspector {
    private final EnvironmentLifecycle lifecycle;
    private final ComponentRuntimeSupervisor components;
    private final RuntimeConnectionRegistry connections;
    private final RuntimeDiagnostics diagnostics;
    private final ScenarioJournal journal;
    private final ProofSubjectRegistry proofSubjects;

    EnvironmentInspector(
        EnvironmentLifecycle lifecycle,
        ComponentRuntimeSupervisor components,
        RuntimeConnectionRegistry connections,
        RuntimeDiagnostics diagnostics,
        ScenarioJournal journal,
        ProofSubjectRegistry proofSubjects
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.components = Objects.requireNonNull(components, "components must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.diagnostics = Objects.requireNonNull(
            diagnostics,
            "diagnostics must not be null"
        );
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.proofSubjects = Objects.requireNonNull(
            proofSubjects,
            "proofSubjects must not be null"
        );
    }

    EnvironmentDiagnostics diagnostics() {
        return diagnostics.capture(
            lifecycle.state(),
            components.components(),
            components::state,
            connections.snapshots()
        );
    }

    ScenarioJournalSnapshot journalSnapshot() {
        return journal.snapshot();
    }

    ProofSubjects proofSubjects() {
        return proofSubjects;
    }

    List<RuntimeConnectionSnapshot> connectionSnapshots() {
        return connections.snapshots();
    }

    RuntimeConnectionSnapshot connectionSnapshot(ConnectionId id) {
        return connections.snapshot(id);
    }
}
