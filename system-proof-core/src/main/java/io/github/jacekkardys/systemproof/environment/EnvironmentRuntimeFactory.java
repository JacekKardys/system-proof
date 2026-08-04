package io.github.jacekkardys.systemproof.environment;

import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.component.AbstractComponent;

/** Validates runtime inputs and assembles immutable execution collaborators. */
final class EnvironmentRuntimeFactory {
    private EnvironmentRuntimeFactory() {}

    static Assembly assemble(
        EnvironmentTopology topology,
        EnvironmentLogging logging
    ) {
        return assemble(topology, logging, ConnectionRouting.direct());
    }

    static Assembly assemble(
        EnvironmentTopology topology,
        EnvironmentLogging logging,
        ConnectionRouting routing
    ) {
        topology = Objects.requireNonNull(topology, "topology must not be null");
        List<AbstractComponent<?, ?>> components = topology.runtimeComponents();
        ComponentExecutionPlan plan = ComponentExecutionPlan.create(
            components,
            topology::connectionFrom
        );
        logging = Objects.requireNonNull(logging, "logging must not be null");
        logging.validateAgainst(topology);
        routing = Objects.requireNonNull(routing, "routing must not be null");

        ScenarioJournal journal = new ScenarioJournal();
        JournalRenderer renderer = new JournalRenderer();
        EnvironmentEventPublisher events = new EnvironmentEventPublisher(
            journal,
            new FailureRedactor(),
            new JournalSlf4jEmitter(logging, renderer)
        );
        ProofSubjectRegistry proofSubjects = new ProofSubjectRegistry(events);
        SemanticControlCoordinator controls = new SemanticControlCoordinator(
            events,
            proofSubjects
        );
        RuntimeConnectionRegistry connections = new RuntimeConnectionRegistry(
            topology.connections(),
            events,
            routing,
            controls,
            proofSubjects
        );
        RuntimeBindings bindings = new RuntimeBindings(connections);
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics(journal, renderer);
        EnvironmentLifecycle lifecycle = new EnvironmentLifecycle(events);
        ComponentRuntimeSupervisor componentSupervisor =
            new ComponentRuntimeSupervisor(plan, bindings, diagnostics, events);
        EnvironmentInspector inspector = new EnvironmentInspector(
            lifecycle,
            componentSupervisor,
            connections,
            diagnostics,
            journal,
            proofSubjects
        );
        EnvironmentExecution execution = new EnvironmentExecution(
            lifecycle,
            componentSupervisor,
            connections,
            controls,
            proofSubjects,
            events,
            inspector
        );

        return new Assembly(
            execution,
            componentSupervisor,
            inspector,
            controls
        );
    }

    record Assembly(
        EnvironmentExecution execution,
        ComponentRuntimeSupervisor components,
        EnvironmentInspector inspector,
        SemanticControlCoordinator controls
    ) {
        Assembly {
            Objects.requireNonNull(execution, "execution must not be null");
            Objects.requireNonNull(components, "components must not be null");
            Objects.requireNonNull(inspector, "inspector must not be null");
            Objects.requireNonNull(controls, "controls must not be null");
        }
    }
}
