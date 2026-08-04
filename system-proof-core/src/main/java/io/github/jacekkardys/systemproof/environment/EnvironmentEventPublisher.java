package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.ProofSubjectArmedEvent;
import io.github.jacekkardys.systemproof.journal.ProofSubjectCreatedEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.SemanticHoldEvent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.proof.CorrelationCardinality;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.control.SemanticHoldFailure;
import io.github.jacekkardys.systemproof.control.SemanticHoldRef;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Builds and publishes framework-owned facts through the single environment journal. */
final class EnvironmentEventPublisher {
    private final ScenarioJournal journal;
    private final FailureRedactor failureRedactor;
    private final JournalSlf4jEmitter emitter;

    EnvironmentEventPublisher(
        ScenarioJournal journal,
        EnvironmentLogging logging
    ) {
        this(
            journal,
            new FailureRedactor(),
            new JournalSlf4jEmitter(logging, new JournalRenderer())
        );
    }

    EnvironmentEventPublisher(
        ScenarioJournal journal,
        FailureRedactor failureRedactor,
        JournalSlf4jEmitter emitter
    ) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.failureRedactor = Objects.requireNonNull(
            failureRedactor,
            "failureRedactor must not be null"
        );
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
    }

    void environmentLifecycle(EnvironmentState state) {
        LogLevel level = state == EnvironmentState.FAILED ? LogLevel.ERROR : LogLevel.INFO;
        emitter.framework(append(new EnvironmentLifecycleEvent(state)), level);
    }

    void componentLifecycle(Component component, ComponentState state) {
        Objects.requireNonNull(component, "component must not be null");
        LogLevel level = state == ComponentState.FAILED ? LogLevel.ERROR : LogLevel.INFO;
        emitter.component(
            append(new ComponentLifecycleEvent(component.id(), state)),
            component,
            level
        );
    }

    void connectionLifecycle(
        ConnectionRef connection,
        ConnectionDescriptor descriptor,
        ConnectionState state,
        RoutingMode routingMode,
        boolean directTargetAvailable,
        boolean consumerTargetAvailable
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        LogLevel level = state == ConnectionState.FAILED ? LogLevel.ERROR : LogLevel.INFO;
        emitter.connection(
            append(new ConnectionLifecycleEvent(
                descriptor,
                state,
                routingMode,
                directTargetAvailable,
                consumerTargetAvailable
            )),
            connection,
            level
        );
    }

    void environmentStartupFailure(Throwable failure) {
        emitter.framework(
            append(new FailureEvent.EnvironmentStartup(failureRedactor.details(failure))),
            LogLevel.ERROR
        );
    }

    void componentStartupFailure(Component component, Throwable failure) {
        Objects.requireNonNull(component, "component must not be null");
        emitter.component(
            append(new FailureEvent.ComponentStartup(
                component.id(),
                failureRedactor.details(failure)
            )),
            component,
            LogLevel.ERROR
        );
    }

    void componentCleanupFailure(Component component, Throwable failure) {
        Objects.requireNonNull(component, "component must not be null");
        emitter.component(
            append(new FailureEvent.ComponentCleanup(
                component.id(),
                failureRedactor.details(failure)
            )),
            component,
            LogLevel.ERROR
        );
    }

    void connectionMaterializationFailure(ConnectionRef connection, Throwable failure) {
        Objects.requireNonNull(connection, "connection must not be null");
        emitter.connection(
            append(new FailureEvent.ConnectionMaterialization(
                connection.id(),
                failureRedactor.details(failure)
            )),
            connection,
            LogLevel.ERROR
        );
    }

    void connectionCleanupFailure(ConnectionRef connection, Throwable failure) {
        Objects.requireNonNull(connection, "connection must not be null");
        emitter.connection(
            append(new FailureEvent.ConnectionCleanup(
                connection.id(),
                failureRedactor.details(failure)
            )),
            connection,
            LogLevel.ERROR
        );
    }

    void driverResourceCleanupFailure(String resourceName, Throwable failure) {
        emitter.framework(
            append(new FailureEvent.DriverResourceCleanup(
                resourceName,
                failureRedactor.details(failure)
            )),
            LogLevel.ERROR
        );
    }

    void framework(LogLevel level, String message) {
        emitter.framework(
            append(new DiagnosticEvent(
                DiagnosticEvent.EnvironmentSubject.INSTANCE,
                level,
                message
            )),
            level
        );
    }

    void connection(ConnectionRef connection, LogLevel level, String message) {
        Objects.requireNonNull(connection, "connection must not be null");
        emitter.connection(
            append(new DiagnosticEvent(
                new DiagnosticEvent.ConnectionSubject(connection.id()),
                level,
                message
            )),
            connection,
            level
        );
    }

    void component(Component component, LogLevel level, String message) {
        Objects.requireNonNull(component, "component must not be null");
        emitter.component(
            append(new DiagnosticEvent(
                new DiagnosticEvent.ComponentSubject(component.id()),
                level,
                message
            )),
            component,
            level
        );
    }

    void protectRoutePreparationFailure(ConnectionRef connection, Throwable failure) {
        Objects.requireNonNull(connection, "connection must not be null");
        failureRedactor.protectRoutePreparation(connection.id(), failure);
    }

    void protectRouteCleanupFailure(ConnectionRef connection, Throwable failure) {
        Objects.requireNonNull(connection, "connection must not be null");
        failureRedactor.protectRouteCleanup(connection.id(), failure);
    }

    void interaction(
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
        emitter.connection(
            append(new InteractionObservationEvent(interactionRef, evidence)),
            connection,
            LogLevel.INFO
        );
    }

    void proofSubjectCreated(ProofSubjectRef proofSubject) {
        emitter.framework(
            append(new ProofSubjectCreatedEvent(proofSubject)),
            LogLevel.INFO
        );
    }

    void proofSubjectArmed(
        ProofSubjectRef proofSubject,
        CorrelationKey key,
        boolean sharedKey
    ) {
        emitter.framework(
            append(new ProofSubjectArmedEvent(proofSubject, key, sharedKey)),
            LogLevel.INFO
        );
    }

    void correlationCandidate(
        Optional<ProofSubjectRef> proofSubject,
        CorrelationKey key,
        InteractionRef interactionRef,
        EvidenceSnapshot nativeReference,
        CorrelationCardinality cardinality
    ) {
        emitter.framework(
            append(new CorrelationCandidateEvent(
                proofSubject,
                key,
                interactionRef,
                nativeReference,
                cardinality
            )),
            cardinality == CorrelationCardinality.AMBIGUOUS
                ? LogLevel.WARN
                : LogLevel.INFO
        );
    }

    void semanticHold(
        SemanticHoldRef holdRef,
        SemanticHoldState state,
        ConnectionId connectionId,
        FlowDirection direction,
        EvidenceSchemaId evidenceSchema,
        Optional<ProofSubjectRef> proofSubject,
        Optional<InteractionRef> interactionRef,
        Optional<SemanticHoldFailure> failure
    ) {
        LogLevel level = state == SemanticHoldState.FAILED
            ? LogLevel.WARN
            : LogLevel.INFO;
        emitter.framework(
            append(new SemanticHoldEvent(
                holdRef,
                state,
                connectionId,
                direction,
                evidenceSchema,
                proofSubject,
                interactionRef,
                failure
            )),
            level
        );
    }

    void checkpoint(
        Component component,
        CheckpointId checkpointId,
        CheckpointEvent.Kind kind,
        CheckpointEvent.Stage stage
    ) {
        Objects.requireNonNull(component, "component must not be null");
        emitter.component(
            append(new CheckpointEvent(component.id(), checkpointId, kind, stage)),
            component,
            LogLevel.INFO
        );
    }

    void disruption(
        Component component,
        DisruptionId disruptionId,
        DisruptionLifecycleEvent.Stage stage
    ) {
        Objects.requireNonNull(component, "component must not be null");
        LogLevel level = stage == DisruptionLifecycleEvent.Stage.FAILED
            ? LogLevel.WARN
            : LogLevel.INFO;
        emitter.component(
            append(new DisruptionLifecycleEvent(component.id(), disruptionId, stage)),
            component,
            level
        );
    }

    private JournalEntry append(ScenarioEvent event) {
        return journal.append(event);
    }
}
