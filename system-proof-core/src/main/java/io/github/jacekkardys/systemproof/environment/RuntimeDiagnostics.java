package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.driver.DiagnosticSource;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;

/** Snapshots framework state before invoking classified diagnostic suppliers during rendering. */
final class RuntimeDiagnostics {
    private static final int MAX_DEFAULT_DIAGNOSTICS_CHARACTERS = 256 * 1024;
    private static final int MAX_DEFAULT_SOURCES = 32;
    private static final int MAX_COMPONENT_STATE_ENTRIES = 128;
    private static final int MAX_CONNECTION_STATE_ENTRIES = 256;
    private static final String TRUNCATION_MARKER = "[DIAGNOSTICS TRUNCATED]";
    private static final String COMPONENTS_OMITTED = "[COMPONENT STATE OMITTED]";
    private static final String CONNECTIONS_OMITTED = "[CONNECTION STATE OMITTED]";

    private final ScenarioJournal journal;
    private final JournalRenderer renderer;
    private final List<OwnedDiagnosticSource> sources = new ArrayList<>();
    private boolean sourcesOmitted;

    RuntimeDiagnostics(ScenarioJournal journal, JournalRenderer renderer) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    }

    synchronized void add(Component component, List<DiagnosticSource> diagnostics) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        int inspected = 0;
        for (DiagnosticSource source : diagnostics) {
            if (inspected == MAX_DEFAULT_SOURCES || sources.size() == MAX_DEFAULT_SOURCES) {
                sourcesOmitted |= diagnostics.size() > inspected;
                break;
            }
            inspected++;
            Objects.requireNonNull(source, "diagnostic source must not be null");
            if (source.classification()
                    == DiagnosticSource.SafetyClassification.REDACTED_TEXT) {
                if (sources.size() < MAX_DEFAULT_SOURCES) {
                    sources.add(new OwnedDiagnosticSource(component.id(), source));
                } else {
                    sourcesOmitted = true;
                }
            }
        }
    }

    Snapshot snapshot(
        EnvironmentState environmentState,
        List<AbstractComponent<?, ?>> components,
        Function<Component, ComponentState> componentState,
        List<RuntimeConnectionSnapshot> connections
    ) {
        Objects.requireNonNull(environmentState, "environmentState must not be null");
        Objects.requireNonNull(components, "components must not be null");
        Objects.requireNonNull(componentState, "componentState must not be null");
        Objects.requireNonNull(connections, "connections must not be null");
        int retainedComponents = Math.min(
            components.size(),
            MAX_COMPONENT_STATE_ENTRIES
        );
        List<ComponentSnapshot> componentSnapshots = new ArrayList<>(retainedComponents);
        for (int index = 0; index < retainedComponents; index++) {
            AbstractComponent<?, ?> component = components.get(index);
            componentSnapshots.add(new ComponentSnapshot(
                component.id(),
                component.type(),
                componentState.apply(component)
            ));
        }
        int retainedConnections = Math.min(
            connections.size(),
            MAX_CONNECTION_STATE_ENTRIES
        );
        List<RuntimeConnectionSnapshot> connectionSnapshot = List.copyOf(
            connections.subList(0, retainedConnections)
        );
        ScenarioJournalSnapshot journalSnapshot = journal.snapshot();
        SourceSnapshot sourceSnapshot = sourceSnapshot();
        return new Snapshot(
            environmentState,
            componentSnapshots,
            components.size() > retainedComponents,
            connectionSnapshot,
            connections.size() > retainedConnections,
            journalSnapshot,
            sourceSnapshot
        );
    }

    EnvironmentDiagnostics render(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        BoundedContent content = new BoundedContent();
        content.append("[STATE] environment=" + snapshot.environmentState);
        for (ComponentSnapshot component : snapshot.components) {
            content.appendLine(
                "[STATE] component=" + component.id()
                    + " type=" + component.type()
                    + " state=" + component.state()
            );
        }
        if (snapshot.componentsOmitted) {
            content.appendLine(COMPONENTS_OMITTED);
        }
        for (RuntimeConnectionSnapshot connection : snapshot.connections) {
            ConnectionDescriptor descriptor = connection.descriptor();
            content.appendLine(
                "[STATE] connection=" + connection.id()
                    + " source=" + descriptor.sourcePortQualifiedName()
                    + " target=" + descriptor.targetPortQualifiedName()
                    + " contract=" + descriptor.contractId()
                    + " contractType=" + descriptor.contractTypeName()
                    + " interaction=" + descriptor.interactionId()
                    + " protocol=" + descriptor.protocolId()
                    + " scheme=" + descriptor.protocolScheme()
                    + " mode=" + connection.routingMode()
                    + " observationRequirement=" + connection.observationRequirement()
                    + " effectiveObservationStatus="
                    + connection.effectiveObservationStatus()
                    + " state=" + connection.state()
                    + " directTargetAvailable=" + connection.directTargetAvailable()
                    + " consumerTargetAvailable=" + connection.consumerTargetAvailable()
            );
        }
        if (snapshot.connectionsOmitted) {
            content.appendLine(CONNECTIONS_OMITTED);
        }
        if (!content.isFull()) {
            String renderedJournal = renderer.render(snapshot.journal);
            if (!renderedJournal.isBlank()) {
                content.appendLine(renderedJournal);
            }
        }
        int capturedSources = 0;
        for (OwnedDiagnosticSource owned : snapshot.sources.sources()) {
            if (owned.source().classification()
                != DiagnosticSource.SafetyClassification.REDACTED_TEXT) {
                continue;
            }
            if (capturedSources == MAX_DEFAULT_SOURCES || content.isFull()) {
                content.truncate();
                break;
            }
            capturedSources++;
            RedactedDiagnosticText captured = captureRedacted(owned.source());
            content.appendLine(
                "[DIAGNOSTIC] [" + owned.componentId() + "] ["
                    + owned.source().sourceId() + "]"
            );
            content.appendLine(captured.content());
        }
        if (snapshot.sources.omitted()) {
            content.truncate();
        }
        return new EnvironmentDiagnostics(content.toString());
    }

    String componentEvents(Component component) {
        Objects.requireNonNull(component, "component must not be null");
        return renderer.renderComponent(journal.snapshot(), component.id());
    }

    private synchronized SourceSnapshot sourceSnapshot() {
        return new SourceSnapshot(List.copyOf(sources), sourcesOmitted);
    }

    private static RedactedDiagnosticText captureRedacted(DiagnosticSource source) {
        try {
            String raw = source.content().get();
            return RedactedDiagnosticText.redact(raw, source.sanitizer());
        } catch (RuntimeException | Error failure) {
            String failureType = FailureDetails.from(failure).failureType();
            return RedactedDiagnosticText.redact(
                "",
                ignored -> "[DIAGNOSTIC SOURCE CAPTURE FAILED type=" + failureType + "]"
            );
        }
    }

    private record ComponentSnapshot(
        ComponentId id,
        ComponentType type,
        ComponentState state
    ) {
        private ComponentSnapshot {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    private record OwnedDiagnosticSource(ComponentId componentId, DiagnosticSource source) {
        private OwnedDiagnosticSource {
            Objects.requireNonNull(componentId, "componentId must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    private record SourceSnapshot(List<OwnedDiagnosticSource> sources, boolean omitted) {}

    static final class Snapshot {
        private final EnvironmentState environmentState;
        private final List<ComponentSnapshot> components;
        private final boolean componentsOmitted;
        private final List<RuntimeConnectionSnapshot> connections;
        private final boolean connectionsOmitted;
        private final ScenarioJournalSnapshot journal;
        private final SourceSnapshot sources;

        private Snapshot(
            EnvironmentState environmentState,
            List<ComponentSnapshot> components,
            boolean componentsOmitted,
            List<RuntimeConnectionSnapshot> connections,
            boolean connectionsOmitted,
            ScenarioJournalSnapshot journal,
            SourceSnapshot sources
        ) {
            this.environmentState = Objects.requireNonNull(
                environmentState,
                "environmentState must not be null"
            );
            this.components = List.copyOf(components);
            this.componentsOmitted = componentsOmitted;
            this.connections = List.copyOf(connections);
            this.connectionsOmitted = connectionsOmitted;
            this.journal = Objects.requireNonNull(journal, "journal must not be null");
            this.sources = Objects.requireNonNull(sources, "sources must not be null");
        }
    }

    private static final class BoundedContent {
        private final StringBuilder content = new StringBuilder();
        private boolean full;

        void append(String value) {
            if (full) {
                return;
            }
            int remaining = MAX_DEFAULT_DIAGNOSTICS_CHARACTERS - content.length();
            if (value.length() <= remaining) {
                content.append(value);
                return;
            }
            int markerSpace = TRUNCATION_MARKER.length()
                + System.lineSeparator().length();
            int prefixLength = Math.max(0, remaining - markerSpace);
            content.append(value, 0, prefixLength);
            truncate();
        }

        void appendLine(String value) {
            if (!content.isEmpty()) {
                append(System.lineSeparator());
            }
            append(value);
        }

        void truncate() {
            if (full) {
                return;
            }
            int markerSpace = TRUNCATION_MARKER.length()
                + (content.isEmpty() ? 0 : System.lineSeparator().length());
            if (content.length() + markerSpace > MAX_DEFAULT_DIAGNOSTICS_CHARACTERS) {
                content.setLength(MAX_DEFAULT_DIAGNOSTICS_CHARACTERS - markerSpace);
            }
            if (!content.isEmpty()) {
                content.append(System.lineSeparator());
            }
            content.append(TRUNCATION_MARKER);
            full = true;
        }

        boolean isFull() {
            return full;
        }

        @Override
        public String toString() {
            return content.toString();
        }
    }
}
