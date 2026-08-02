package io.github.jacekkardys.systemproof.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.DiagnosticSource;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.component.ComponentState;
import io.github.jacekkardys.systemproof.model.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;
import io.github.jacekkardys.systemproof.model.runtime.RuntimeConnectionSnapshot;

/** Captures lifecycle events and component-owned diagnostic sources without running cleanup. */
final class RuntimeDiagnostics {
    private final ScenarioJournal journal;
    private final EnvironmentEventLog eventLog;
    private final List<OwnedDiagnosticSource> sources = new ArrayList<>();

    RuntimeDiagnostics(ScenarioJournal journal, EnvironmentEventLog eventLog) {
        this.journal = journal;
        this.eventLog = eventLog;
    }

    void add(Component component, List<DiagnosticSource> diagnostics) {
        diagnostics.forEach(source -> sources.add(new OwnedDiagnosticSource(component, source)));
    }

    EnvironmentDiagnostics capture(
        EnvironmentState environmentState,
        List<AbstractComponent<?, ?>> components,
        Function<Component, ComponentState> componentState,
        List<RuntimeConnectionSnapshot> connections
    ) {
        List<RuntimeConnectionSnapshot> connectionSnapshot = List.copyOf(connections);
        ScenarioJournalSnapshot snapshot = journal.snapshot();
        StringBuilder content = new StringBuilder();
        content.append("[STATE] environment=").append(environmentState);
        for (AbstractComponent<?, ?> component : components) {
            content.append(System.lineSeparator())
                .append("[STATE] component=").append(component.id())
                .append(" type=").append(component.type())
                .append(" state=").append(componentState.apply(component));
        }
        for (RuntimeConnectionSnapshot connection : connectionSnapshot) {
            ConnectionDescriptor descriptor = connection.descriptor();
            content.append(System.lineSeparator())
                .append("[STATE] connection=").append(connection.id())
                .append(" source=").append(descriptor.sourcePortQualifiedName())
                .append(" target=").append(descriptor.targetPortQualifiedName())
                .append(" contract=").append(descriptor.contractId())
                .append(" contractType=").append(descriptor.contractTypeName())
                .append(" interaction=").append(descriptor.interactionId())
                .append(" protocol=").append(descriptor.protocolId())
                .append(" scheme=").append(descriptor.protocolScheme())
                .append(" mode=").append(connection.routingMode())
                .append(" observationRequirement=").append(
                    connection.observationRequirement()
                )
                .append(" effectiveObservationStatus=").append(
                    connection.effectiveObservationStatus()
                )
                .append(" state=").append(connection.state())
                .append(" directTargetAvailable=").append(
                    connection.directTargetAvailable()
                )
                .append(" consumerTargetAvailable=").append(
                    connection.consumerTargetAvailable()
                );
        }
        String renderedJournal = eventLog.render(snapshot).content();
        if (!renderedJournal.isBlank()) {
            content.append(System.lineSeparator()).append(renderedJournal);
        }
        for (OwnedDiagnosticSource owned : sources) {
            String captured;
            try {
                captured = owned.source().content().get();
            } catch (RuntimeException failure) {
                captured = "Diagnostic capture failed: " + failure;
            }
            if (captured != null && !captured.isBlank()) {
                content.append(System.lineSeparator())
                    .append("[DIAGNOSTIC] [").append(owned.component().id()).append("] [")
                    .append(owned.source().name()).append("]")
                    .append(System.lineSeparator()).append(captured);
            }
        }
        return EnvironmentDiagnostics.diagnostics(content.toString());
    }

    private record OwnedDiagnosticSource(Component component, DiagnosticSource source) {}
}
