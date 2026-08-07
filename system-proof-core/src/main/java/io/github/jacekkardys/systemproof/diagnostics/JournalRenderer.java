package io.github.jacekkardys.systemproof.diagnostics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.ProofSubjectArmedEvent;
import io.github.jacekkardys.systemproof.journal.ProofSubjectCreatedEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.journal.SemanticHoldEvent;
import io.github.jacekkardys.systemproof.journal.SemanticPredecessorGuardEvent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;

/** Linear human-readable rendering over detached immutable journal read models. */
public final class JournalRenderer {
    private static final int MAX_RENDERED_CHARACTERS = 256 * 1024;
    private static final int MAX_RENDERED_LINE_CHARACTERS = 8 * 1024;
    private static final String LINE_TRUNCATION_MARKER = "[TRUNCATED]";
    private static final String TRUNCATION_MARKER = "[DIAGNOSTICS TRUNCATED]";
    /** Renders the complete supplied snapshot in journal storage order. */
    public String render(ScenarioJournalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return renderEntries(snapshot.entries());
    }

    /** Renders entries associated with one stable component identity. */
    public String renderComponent(
        ScenarioJournalSnapshot snapshot,
        ComponentId componentId
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(componentId, "componentId must not be null");
        return renderEntries(snapshot.entries().stream()
            .filter(entry -> concerns(entry.event(), componentId))
            .toList());
    }

    /** Expands one entry into identically prefixed lines for SLF4J emission. */
    public List<String> renderLines(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        RenderedEvent rendered = describe(entry.event());
        String prefix = timestamp(entry.diagnosticElapsedTime().orElse(null))
            + " " + rendered.labels() + " ";
        List<String> lines = new ArrayList<>();
        rendered.message().lines().forEachOrdered(line ->
            lines.add(boundLine(prefix + line))
        );
        return List.copyOf(lines);
    }

    private static String boundLine(String line) {
        if (line.length() <= MAX_RENDERED_LINE_CHARACTERS) {
            return line;
        }
        return line.substring(
            0,
            MAX_RENDERED_LINE_CHARACTERS - LINE_TRUNCATION_MARKER.length()
        ) + LINE_TRUNCATION_MARKER;
    }

    private String renderEntries(List<JournalEntry> entries) {
        StringBuilder rendered = new StringBuilder();
        boolean firstLine = true;
        for (JournalEntry entry : entries) {
            for (String line : renderLines(entry)) {
                int separatorCharacters = firstLine ? 0 : System.lineSeparator().length();
                int required = separatorCharacters + line.length();
                if (rendered.length() + required > MAX_RENDERED_CHARACTERS) {
                    appendTruncationMarker(rendered, firstLine);
                    return rendered.toString();
                }
                if (separatorCharacters > 0) {
                    rendered.append(System.lineSeparator());
                }
                rendered.append(line);
                firstLine = false;
            }
        }
        return rendered.toString();
    }

    private static void appendTruncationMarker(StringBuilder rendered, boolean empty) {
        int separatorCharacters = empty ? 0 : System.lineSeparator().length();
        int markerSpace = separatorCharacters + TRUNCATION_MARKER.length();
        if (rendered.length() + markerSpace > MAX_RENDERED_CHARACTERS) {
            rendered.setLength(MAX_RENDERED_CHARACTERS - markerSpace);
        }
        if (!empty) {
            rendered.append(System.lineSeparator());
        }
        rendered.append(TRUNCATION_MARKER);
    }

    private static RenderedEvent describe(ScenarioEvent event) {
        return switch (event) {
            case EnvironmentLifecycleEvent lifecycle -> new RenderedEvent(
                "[FRAMEWORK] [environment]",
                environmentLifecycleMessage(lifecycle.state())
            );
            case ComponentLifecycleEvent lifecycle -> new RenderedEvent(
                componentLabels(lifecycle.componentId()),
                componentLifecycleMessage(lifecycle.state())
            );
            case ConnectionLifecycleEvent lifecycle -> new RenderedEvent(
                connectionLabels(lifecycle.connection().id()),
                connectionLifecycleMessage(lifecycle)
            );
            case DiagnosticEvent diagnostic -> new RenderedEvent(
                diagnosticLabels(diagnostic.subject()),
                diagnostic.message().content()
            );
            case FailureEvent.EnvironmentStartup failure -> new RenderedEvent(
                "[FRAMEWORK] [environment]",
                "Environment startup failed: " + failureMessage(failure.failure())
            );
            case FailureEvent.ComponentStartup failure -> new RenderedEvent(
                componentLabels(failure.componentId()),
                "Component startup failed: " + failureMessage(failure.failure())
            );
            case FailureEvent.ComponentCleanup failure -> new RenderedEvent(
                componentLabels(failure.componentId()),
                "Component cleanup failed: " + failureMessage(failure.failure())
            );
            case FailureEvent.ConnectionMaterialization failure -> new RenderedEvent(
                connectionLabels(failure.connectionId()),
                "Connection materialization failed: " + failureMessage(failure.failure())
            );
            case FailureEvent.ConnectionCleanup failure -> new RenderedEvent(
                connectionLabels(failure.connectionId()),
                "Connection cleanup failed: " + failureMessage(failure.failure())
            );
            case FailureEvent.DriverResourceCleanup failure -> new RenderedEvent(
                "[FRAMEWORK] [environment]",
                "Driver resource '" + failure.resourceName() + "' cleanup failed: "
                    + failureMessage(failure.failure())
            );
            case InteractionObservationEvent observation -> new RenderedEvent(
                interactionLabels(observation),
                interactionMessage(observation)
            );
            case ProofSubjectCreatedEvent created -> new RenderedEvent(
                proofSubjectLabels(created.proofSubject()),
                "Created proof subject"
            );
            case ProofSubjectArmedEvent armed -> new RenderedEvent(
                proofSubjectLabels(armed.proofSubject()),
                "Armed proof subject keySchema=" + armed.key().schema()
                    + " sharedKey=" + armed.sharedKey()
            );
            case CorrelationCandidateEvent candidate -> new RenderedEvent(
                correlationLabels(candidate),
                correlationMessage(candidate)
            );
            case SemanticHoldEvent hold -> new RenderedEvent(
                semanticHoldLabels(hold),
                semanticHoldMessage(hold)
            );
            case SemanticPredecessorGuardEvent guard -> new RenderedEvent(
                semanticPredecessorGuardLabels(guard),
                semanticPredecessorGuardMessage(guard)
            );
            case CheckpointEvent checkpoint -> new RenderedEvent(
                "[CHECKPOINT] [" + checkpoint.observingComponentId() + "] ["
                    + checkpoint.checkpointId().value() + "]",
                "Recorded " + checkpoint.kind().name().toLowerCase(Locale.ROOT)
                    + " stage=" + checkpoint.stage()
            );
            case DisruptionLifecycleEvent disruption -> new RenderedEvent(
                "[DISRUPTION] [" + disruption.observingComponentId() + "] ["
                    + disruption.disruptionId().value() + "]",
                "Recorded disruption stage=" + disruption.stage()
            );
            default -> {
                String eventType = normalizedType(event.getClass());
                yield new RenderedEvent(
                    "[EVENT] [" + eventType + "]",
                    "Recorded scenario event type=" + eventType
                );
            }
        };
    }

    private static String normalizedType(Class<?> type) {
        String name = type.getSimpleName();
        if (name == null || name.isBlank()) {
            return "ScenarioEvent";
        }
        StringBuilder normalized = new StringBuilder(Math.min(name.length(), 128));
        for (int index = 0; index < name.length() && normalized.length() < 128; index++) {
            char character = name.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_'
                || character == '$') {
                normalized.append(character);
            }
        }
        return normalized.isEmpty() ? "ScenarioEvent" : normalized.toString();
    }

    private static boolean concerns(ScenarioEvent event, ComponentId componentId) {
        return switch (event) {
            case ComponentLifecycleEvent lifecycle ->
                lifecycle.componentId().equals(componentId);
            case ConnectionLifecycleEvent lifecycle ->
                lifecycle.connection().sourceComponentId().equals(componentId)
                    || lifecycle.connection().targetComponentId().equals(componentId);
            case DiagnosticEvent diagnostic ->
                diagnostic.subject() instanceof DiagnosticEvent.ComponentSubject subject
                    && subject.componentId().equals(componentId);
            case FailureEvent.ComponentStartup failure ->
                failure.componentId().equals(componentId);
            case FailureEvent.ComponentCleanup failure ->
                failure.componentId().equals(componentId);
            case CheckpointEvent checkpoint ->
                checkpoint.observingComponentId().equals(componentId);
            case DisruptionLifecycleEvent disruption ->
                disruption.observingComponentId().equals(componentId);
            case InteractionObservationEvent observation -> false;
            case ProofSubjectCreatedEvent created -> false;
            case ProofSubjectArmedEvent armed -> false;
            case CorrelationCandidateEvent candidate -> false;
            case SemanticHoldEvent hold -> false;
            case SemanticPredecessorGuardEvent guard -> false;
            case EnvironmentLifecycleEvent lifecycle -> false;
            case FailureEvent.EnvironmentStartup failure -> false;
            case FailureEvent.DriverResourceCleanup failure -> false;
            case FailureEvent.ConnectionMaterialization failure -> false;
            case FailureEvent.ConnectionCleanup failure -> false;
            default -> false;
        };
    }

    private static String diagnosticLabels(DiagnosticEvent.Subject subject) {
        return switch (subject) {
            case DiagnosticEvent.EnvironmentSubject environment ->
                "[FRAMEWORK] [environment]";
            case DiagnosticEvent.ComponentSubject component ->
                componentLabels(component.componentId());
            case DiagnosticEvent.ConnectionSubject connection ->
                connectionLabels(connection.connectionId());
        };
    }

    private static String connectionLabels(ConnectionId connectionId) {
        return "[CONNECTION] [" + connectionId + "]";
    }

    private static String componentLabels(ComponentId componentId) {
        return "[COMPONENT] [" + componentId + "]";
    }

    private static String interactionLabels(InteractionObservationEvent observation) {
        InteractionRef reference = observation.interactionRef();
        return "[INTERACTION]"
            + " [connection=" + reference.connectionId() + "]"
            + " [session=" + reference.sessionId() + "]"
            + " [flow=" + reference.direction() + "]"
            + " [ordinal=" + reference.ordinal() + "]"
            + " [ref=" + reference + "]";
    }

    private static String interactionMessage(InteractionObservationEvent observation) {
        EvidenceSchemaId schemaId = observation.evidence().schemaId();
        return "Observed typed evidence"
            + " schema=" + schemaId.namespace() + ":" + schemaId.name()
            + " version=" + schemaId.version()
            + " encodedBytes=" + observation.evidence().encodedSize();
    }

    private static String proofSubjectLabels(ProofSubjectRef proofSubject) {
        return "[PROOF-SUBJECT] [ref=" + proofSubject + "]";
    }

    private static String correlationLabels(CorrelationCandidateEvent candidate) {
        return "[CORRELATION]"
            + candidate.proofSubject()
                .map(subject -> " [subject=" + subject + "]")
                .orElse(" [subject=unassigned]")
            + " [connection=" + candidate.interactionRef().connectionId() + "]"
            + " [interaction=" + candidate.interactionRef() + "]";
    }

    private static String correlationMessage(CorrelationCandidateEvent candidate) {
        EvidenceSchemaId schemaId = candidate.nativeReference().schemaId();
        return "Published correlation candidate"
            + " keySchema=" + candidate.key().schema()
            + " nativeReferenceSchema=" + schemaId.namespace() + ":" + schemaId.name()
            + ":v" + schemaId.version()
            + " encodedBytes=" + candidate.nativeReference().encodedSize()
            + " cardinality=" + candidate.cardinality();
    }

    private static String semanticHoldLabels(SemanticHoldEvent hold) {
        return "[SEMANTIC-HOLD]"
            + " [ref=" + hold.holdRef() + "]"
            + " [connection=" + hold.connectionId() + "]"
            + " [flow=" + hold.direction() + "]"
            + hold.proofSubject()
                .map(subject -> " [subject=" + subject + "]")
                .orElse("");
    }

    private static String semanticHoldMessage(SemanticHoldEvent hold) {
        EvidenceSchemaId schema = hold.evidenceSchema();
        return "Semantic hold state=" + hold.state()
            + " evidenceSchema=" + schema.namespace() + ":" + schema.name()
            + ":v" + schema.version()
            + hold.interactionRef()
                .map(interaction -> " interaction=" + interaction)
                .orElse("")
            + hold.failure()
                .map(failure -> " failure=" + failure)
                .orElse("");
    }

    private static String semanticPredecessorGuardLabels(
        SemanticPredecessorGuardEvent guard
    ) {
        return "[SEMANTIC-PREDECESSOR-GUARD]"
            + " [ref=" + guard.guardRef() + "]"
            + " [subject=" + guard.proofSubject() + "]";
    }

    private static String semanticPredecessorGuardMessage(
        SemanticPredecessorGuardEvent guard
    ) {
        return "Semantic predecessor guard kind=" + guard.kind()
            + " state=" + guard.state()
            + " requiredBoundary=" + guard.requiredBoundary()
            + guard.predecessor()
                .map(interaction -> " predecessor=" + interaction)
                .orElse("")
            + guard.successor()
                .map(interaction -> " successor=" + interaction)
                .orElse("")
            + guard.decision()
                .map(decision -> " decision=" + decision)
                .orElse("")
            + guard.violation()
                .map(violation -> " violation=" + violation)
                .orElse("")
            + guard.failure()
                .map(failure -> " failure=" + failure)
                .orElse("");
    }

    private static String environmentLifecycleMessage(EnvironmentState state) {
        return switch (state) {
            case DECLARED -> "Environment declared";
            case STARTING -> "Starting environment";
            case RUNNING -> "Environment started";
            case STOPPING -> "Stopping environment";
            case STOPPED -> "Environment stopped";
            case FAILED -> "Environment failed";
        };
    }

    private static String componentLifecycleMessage(ComponentState state) {
        return switch (state) {
            case DECLARED -> "Component declared";
            case STARTING -> "Starting component";
            case RUNNING -> "Component ready";
            case STOPPING -> "Stopping component";
            case STOPPED -> "Component stopped";
            case FAILED -> "Component failed";
        };
    }

    private static String connectionLifecycleMessage(ConnectionLifecycleEvent lifecycle) {
        ConnectionDescriptor connection = lifecycle.connection();
        String action = switch (lifecycle.state()) {
            case DECLARED -> "Connection materialized and validated";
            case STARTING -> "Starting connection";
            case RUNNING -> "Consumer target available";
            case STOPPING -> "Stopping connection";
            case STOPPED -> "Connection stopped";
            case FAILED -> "Connection failed";
        };
        return action
            + " source=" + connection.sourcePortQualifiedName()
            + " target=" + connection.targetPortQualifiedName()
            + " contract=" + connection.contractId()
            + " contractType=" + connection.contractTypeName()
            + " interaction=" + connection.interactionId()
            + " protocol=" + connection.protocolId()
            + " scheme=" + connection.protocolScheme()
            + " state=" + lifecycle.state()
            + " mode=" + lifecycle.routingMode()
            + " directTargetAvailable=" + lifecycle.directTargetAvailable()
            + " consumerTargetAvailable=" + lifecycle.consumerTargetAvailable();
    }

    private static String failureMessage(FailureDetails failure) {
        return failure.failureType();
    }

    private static String timestamp(Duration elapsed) {
        if (elapsed == null) {
            return "T+--:--:--.---";
        }
        long elapsedMillis = elapsed.toMillis();
        long hours = elapsedMillis / TimeUnit.HOURS.toMillis(1);
        long minutes = elapsedMillis / TimeUnit.MINUTES.toMillis(1) % 60;
        long seconds = elapsedMillis / TimeUnit.SECONDS.toMillis(1) % 60;
        long millis = elapsedMillis % 1_000;
        return String.format(
            Locale.ROOT,
            "T+%02d:%02d:%02d.%03d",
            hours,
            minutes,
            seconds,
            millis
        );
    }

    private record RenderedEvent(String labels, String message) {}
}
