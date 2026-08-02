package io.github.jacekkardys.systemproof.engine.execution;

import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.routing.ConnectionRouting;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;

/** Validates runtime inputs and assembles immutable execution collaborators. */
final class EnvironmentRuntimeFactory {
    private EnvironmentRuntimeFactory() {}

    static EnvironmentRuntime create(
        EnvironmentTopology topology,
        EnvironmentLogging logging
    ) {
        return create(topology, logging, ConnectionRouting.direct());
    }

    static EnvironmentRuntime create(
        EnvironmentTopology topology,
        EnvironmentLogging logging,
        ConnectionRouting routing
    ) {
        topology = Objects.requireNonNull(topology, "topology must not be null");
        List<AbstractComponent<?, ?>> components = topology.runtimeComponents();
        List<AbstractComponent<?, ?>> startOrder =
            ComponentStartPlan.order(components, topology::connectionFrom);
        logging = Objects.requireNonNull(logging, "logging must not be null");
        logging.validateAgainst(topology);
        routing = Objects.requireNonNull(routing, "routing must not be null");

        ScenarioJournal journal = new ScenarioJournal();
        EnvironmentEventLog eventLog = new EnvironmentEventLog(journal, logging);
        ProofSubjectRegistry proofSubjects = new ProofSubjectRegistry(eventLog);
        RuntimeConnectionRegistry connections = new RuntimeConnectionRegistry(
            topology.connections(),
            eventLog,
            routing,
            proofSubjects
        );
        RuntimeBindings bindings = new RuntimeBindings(connections);
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics(journal, eventLog);
        EnvironmentLifecycle lifecycle = new EnvironmentLifecycle(components, eventLog);

        return new EnvironmentRuntime(
            components,
            startOrder,
            connections,
            bindings,
            journal,
            eventLog,
            proofSubjects,
            diagnostics,
            lifecycle
        );
    }
}
