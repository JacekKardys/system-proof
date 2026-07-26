package io.github.jacekkardys.systemproof.diagnostics;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.journal.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.InteractionRef;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.ProofSubjectArmedEvent;
import io.github.jacekkardys.systemproof.journal.ProofSubjectCreatedEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.ConnectionState;
import io.github.jacekkardys.systemproof.model.EnvironmentState;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RoutingMode;
import io.github.jacekkardys.systemproof.engine.CorrelationCardinality;
import io.github.jacekkardys.systemproof.engine.CorrelationKey;
import io.github.jacekkardys.systemproof.engine.ProofSubjectRef;

/**
 * Appending and textual rendering view over one supplied {@link ScenarioJournal}.
 *
 * <p>This view owns no independent history. Logging thresholds affect only SLF4J emission; every
 * event is appended before the threshold is evaluated.
 */
public final class EnvironmentEventLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentEventLog.class);

    private final ScenarioJournal journal;
    private final EnvironmentLogging configuration;
    private final Map<Throwable, FailureDetails> protectedFailures = new IdentityHashMap<>();

    public EnvironmentEventLog(ScenarioJournal journal, EnvironmentLogging configuration) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration must not be null"
        );
    }

    public void environmentLifecycle(EnvironmentState state) {
        LogLevel level = state == EnvironmentState.FAILED ? LogLevel.ERROR : LogLevel.INFO;
        append(
            new EnvironmentLifecycleEvent(state),
            configuration.frameworkLevel(),
            level
        );
    }

    public void componentLifecycle(Component component, ComponentState state) {
        Objects.requireNonNull(component, "component must not be null");
        LogLevel level = state == ComponentState.FAILED ? LogLevel.ERROR : LogLevel.INFO;
        append(
            new ComponentLifecycleEvent(component.id(), state),
            configuration.componentLevel(component),
            level
        );
    }

    public void connectionLifecycle(
        ConnectionRef connection,
        ConnectionDescriptor descriptor,
        ConnectionState state,
        RoutingMode routingMode,
        boolean directTargetAvailable,
        boolean consumerTargetAvailable
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        LogLevel level = state == ConnectionState.FAILED ? LogLevel.ERROR : LogLevel.INFO;
        append(
            new ConnectionLifecycleEvent(
                descriptor,
                state,
                routingMode,
                directTargetAvailable,
                consumerTargetAvailable
            ),
            configuration.connectionLevel(connection),
            level
        );
    }

    public void environmentStartupFailure(Throwable failure) {
        append(
            new FailureEvent.EnvironmentStartup(failureDetails(failure)),
            configuration.frameworkLevel(),
            LogLevel.ERROR
        );
    }

    public void componentStartupFailure(Component component, Throwable failure) {
        Objects.requireNonNull(component, "component must not be null");
        append(
            new FailureEvent.ComponentStartup(component.id(), failureDetails(failure)),
            configuration.componentLevel(component),
            LogLevel.ERROR
        );
    }

    public void componentCleanupFailure(Component component, Throwable failure) {
        Objects.requireNonNull(component, "component must not be null");
        append(
            new FailureEvent.ComponentCleanup(component.id(), failureDetails(failure)),
            configuration.componentLevel(component),
            LogLevel.ERROR
        );
    }

    public void connectionMaterializationFailure(
        ConnectionRef connection,
        Throwable failure
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        append(
            new FailureEvent.ConnectionMaterialization(
                connection.id(),
                failureDetails(failure)
            ),
            configuration.connectionLevel(connection),
            LogLevel.ERROR
        );
    }

    public void connectionCleanupFailure(ConnectionRef connection, Throwable failure) {
        Objects.requireNonNull(connection, "connection must not be null");
        append(
            new FailureEvent.ConnectionCleanup(
                connection.id(),
                failureDetails(failure)
            ),
            configuration.connectionLevel(connection),
            LogLevel.ERROR
        );
    }

    public void driverResourceCleanupFailure(String resourceName, Throwable failure) {
        append(
            new FailureEvent.DriverResourceCleanup(
                resourceName,
                failureDetails(failure)
            ),
            configuration.frameworkLevel(),
            LogLevel.ERROR
        );
    }

    public void framework(LogLevel level, String message) {
        append(
            new DiagnosticEvent(
                DiagnosticEvent.EnvironmentSubject.INSTANCE,
                level,
                message
            ),
            configuration.frameworkLevel(),
            level
        );
    }

    public void connection(ConnectionRef connection, LogLevel level, String message) {
        Objects.requireNonNull(connection, "connection must not be null");
        append(
            new DiagnosticEvent(
                new DiagnosticEvent.ConnectionSubject(connection.id()),
                level,
                message
            ),
            configuration.connectionLevel(connection),
            level
        );
    }

    public void component(Component component, LogLevel level, String message) {
        Objects.requireNonNull(component, "component must not be null");
        append(
            new DiagnosticEvent(
                new DiagnosticEvent.ComponentSubject(component.id()),
                level,
                message
            ),
            configuration.componentLevel(component),
            level
        );
    }

    public synchronized void protectRoutePreparationFailure(
        ConnectionRef connection,
        Throwable failure
    ) {
        protectRouteFailure("preparation", connection, failure);
    }

    public synchronized void protectRouteCleanupFailure(
        ConnectionRef connection,
        Throwable failure
    ) {
        protectRouteFailure("cleanup", connection, failure);
    }

    /** Appends one connection-scoped interaction whose evidence was already captured. */
    public void interaction(
        ConnectionRef connection,
        InteractionRef interactionRef,
        EvidenceSnapshot evidence
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(interactionRef, "interactionRef must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (!connection.id().equals(interactionRef.connectionId())) {
            throw new IllegalArgumentException(
                "Interaction reference connection '" + interactionRef.connectionId()
                    + "' does not match bound connection '" + connection.id() + "'"
            );
        }
        append(
            new InteractionObservationEvent(interactionRef, evidence),
            configuration.connectionLevel(connection),
            LogLevel.INFO
        );
    }

    /** Appends one opaque proof-subject allocation fact. */
    public void proofSubjectCreated(ProofSubjectRef proofSubject) {
        append(
            new ProofSubjectCreatedEvent(proofSubject),
            configuration.frameworkLevel(),
            LogLevel.INFO
        );
    }

    /** Appends one safe proof-subject key association fact. */
    public void proofSubjectArmed(
        ProofSubjectRef proofSubject,
        CorrelationKey key,
        boolean sharedKey
    ) {
        append(
            new ProofSubjectArmedEvent(proofSubject, key, sharedKey),
            configuration.frameworkLevel(),
            LogLevel.INFO
        );
    }

    /** Appends one typed correlation publication and its explicit resulting cardinality. */
    public void correlationCandidate(
        Optional<ProofSubjectRef> proofSubject,
        CorrelationKey key,
        InteractionRef interactionRef,
        EvidenceSnapshot nativeReference,
        CorrelationCardinality cardinality
    ) {
        append(
            new CorrelationCandidateEvent(
                proofSubject,
                key,
                interactionRef,
                nativeReference,
                cardinality
            ),
            configuration.frameworkLevel(),
            cardinality == CorrelationCardinality.AMBIGUOUS
                ? LogLevel.WARN
                : LogLevel.INFO
        );
    }

    public void checkpoint(
        Component component,
        CheckpointId checkpointId,
        CheckpointEvent.Kind kind,
        CheckpointEvent.Stage stage
    ) {
        Objects.requireNonNull(component, "component must not be null");
        append(
            new CheckpointEvent(component.id(), checkpointId, kind, stage),
            configuration.componentLevel(component),
            LogLevel.INFO
        );
    }

    public void disruption(
        Component component,
        DisruptionId disruptionId,
        DisruptionLifecycleEvent.Stage stage
    ) {
        Objects.requireNonNull(component, "component must not be null");
        LogLevel level = stage == DisruptionLifecycleEvent.Stage.FAILED
            ? LogLevel.WARN
            : LogLevel.INFO;
        append(
            new DisruptionLifecycleEvent(component.id(), disruptionId, stage),
            configuration.componentLevel(component),
            level
        );
    }

    public EnvironmentDiagnostics snapshot() {
        return render(journal.snapshot());
    }

    /**
     * Renders exactly one supplied immutable snapshot.
     *
     * <p>Line order is journal storage order only and carries no causal meaning.
     */
    public EnvironmentDiagnostics render(ScenarioJournalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return EnvironmentDiagnostics.diagnostics(renderEntries(snapshot.entries()));
    }

    public String componentSnapshot(ComponentId componentId) {
        return componentSnapshot(journal.snapshot(), componentId);
    }

    /**
     * Renders entries associated with one stable component identity from one supplied snapshot.
     */
    public String componentSnapshot(
        ScenarioJournalSnapshot snapshot,
        ComponentId componentId
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(componentId, "componentId must not be null");
        return renderEntries(snapshot.entries().stream()
            .filter(entry -> concerns(entry.event(), componentId))
            .toList());
    }

    private void append(ScenarioEvent event, LogLevel threshold, LogLevel emissionLevel) {
        Objects.requireNonNull(threshold, "threshold must not be null");
        Objects.requireNonNull(emissionLevel, "emissionLevel must not be null");
        JournalEntry entry = journal.append(event);
        if (threshold.includes(emissionLevel)) {
            renderedLines(entry).forEach(line -> emit(emissionLevel, line));
        }
    }

    private static String renderEntries(List<JournalEntry> entries) {
        return entries.stream()
            .flatMap(entry -> renderedLines(entry).stream())
            .reduce((left, right) -> left + System.lineSeparator() + right)
            .orElse("");
    }

    private static List<String> renderedLines(JournalEntry entry) {
        RenderedEvent rendered = describe(entry.event());
        String prefix = timestamp(entry.diagnosticElapsedTime().orElse(null))
            + " " + rendered.labels() + " ";
        return rendered.message().lines().map(line -> prefix + line).toList();
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
                diagnostic.message()
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
        };
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
            case InteractionObservationEvent observation -> false;
            case ProofSubjectCreatedEvent created -> false;
            case ProofSubjectArmedEvent armed -> false;
            case CorrelationCandidateEvent candidate -> false;
            case CheckpointEvent checkpoint ->
                checkpoint.observingComponentId().equals(componentId);
            case DisruptionLifecycleEvent disruption ->
                disruption.observingComponentId().equals(componentId);
            case EnvironmentLifecycleEvent lifecycle -> false;
            case FailureEvent.EnvironmentStartup failure -> false;
            case FailureEvent.DriverResourceCleanup failure -> false;
            case FailureEvent.ConnectionMaterialization failure -> false;
            case FailureEvent.ConnectionCleanup failure -> false;
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
        return failure.failureType()
            + failure.message().map(message -> " - " + message).orElse("");
    }

    private void protectRouteFailure(
        String stage,
        ConnectionRef connection,
        Throwable failure
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        String simpleName = failure.getClass().getSimpleName();
        String type = simpleName.isBlank() ? failure.getClass().getName() : simpleName;
        protectedFailures.putIfAbsent(
            failure,
            new FailureDetails(
                type,
                Optional.of(
                    "Route " + stage + " failed for connection '" + connection.id() + "'"
                )
            )
        );
    }

    private synchronized FailureDetails failureDetails(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        FailureDetails protectedFailure = protectedFailures.get(failure);
        return protectedFailure == null ? FailureDetails.from(failure) : protectedFailure;
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

    private static void emit(LogLevel level, String message) {
        switch (level) {
            case ERROR -> LOGGER.error(message);
            case WARN -> LOGGER.warn(message);
            case INFO -> LOGGER.info(message);
            case DEBUG -> LOGGER.debug(message);
            case TRACE -> LOGGER.trace(message);
            case OFF -> {
                // OFF events remain in the journal but are never emitted.
            }
        }
    }

    private record RenderedEvent(String labels, String message) {
    }
}
