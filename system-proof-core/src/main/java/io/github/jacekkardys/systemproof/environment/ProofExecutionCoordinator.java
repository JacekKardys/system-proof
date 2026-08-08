package io.github.jacekkardys.systemproof.environment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import io.github.jacekkardys.systemproof.control.SemanticHoldFailure;
import io.github.jacekkardys.systemproof.control.SemanticHoldRef;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardFailure;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardRef;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardState;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.SemanticHoldEvent;
import io.github.jacekkardys.systemproof.journal.SemanticPredecessorGuardEvent;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.proof.CorrelationCardinality;
import io.github.jacekkardys.systemproof.proof.ProofConfigurationException;
import io.github.jacekkardys.systemproof.proof.ProofDiagnostic;
import io.github.jacekkardys.systemproof.proof.ProofEvidenceKind;
import io.github.jacekkardys.systemproof.proof.ProofExecution;
import io.github.jacekkardys.systemproof.proof.ProofExecutionState;
import io.github.jacekkardys.systemproof.proof.ProofFailureStage;
import io.github.jacekkardys.systemproof.proof.ProofObligationResolution;
import io.github.jacekkardys.systemproof.proof.ProofOutcome;
import io.github.jacekkardys.systemproof.proof.ProofPlan;
import io.github.jacekkardys.systemproof.proof.ProofPrerequisite;
import io.github.jacekkardys.systemproof.proof.ProofPrerequisiteStatus;
import io.github.jacekkardys.systemproof.proof.ProofRequirementKind;
import io.github.jacekkardys.systemproof.proof.ProofResolution;
import io.github.jacekkardys.systemproof.proof.ProofResolutionReason;
import io.github.jacekkardys.systemproof.proof.ProofResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/**
 * One environment-owned proof activation, typed current-state index, evaluator, and terminal
 * outcome linearization point.
 */
final class ProofExecutionCoordinator
    implements ProofFactObserver, ProofObservationListener {

    private static final int MAX_SECONDARY_DIAGNOSTICS = 32;

    private final Object prerequisiteOwner = new Object();
    private final DeadlineScheduler deadlineScheduler;
    private final ProofOutcomeEvaluator outcomeEvaluator;
    private ProofSubjectRegistry proofSubjects;
    private SemanticControlCoordinator controls;
    private RuntimeConnectionRegistry connections;
    private final Set<ConnectionId> failedRequiredObservations = new LinkedHashSet<>();
    private ExecutionRecord execution;
    private boolean bound;
    private boolean closed;

    ProofExecutionCoordinator() {
        this(new SystemDeadlineScheduler(), ProofOutcomeEvaluator.failClosed());
    }

    ProofExecutionCoordinator(DeadlineScheduler deadlineScheduler) {
        this(deadlineScheduler, ProofOutcomeEvaluator.failClosed());
    }

    ProofExecutionCoordinator(
        DeadlineScheduler deadlineScheduler,
        ProofOutcomeEvaluator outcomeEvaluator
    ) {
        this.deadlineScheduler = Objects.requireNonNull(
            deadlineScheduler,
            "deadlineScheduler must not be null"
        );
        this.outcomeEvaluator = Objects.requireNonNull(
            outcomeEvaluator,
            "outcomeEvaluator must not be null"
        );
    }

    synchronized void bind(
        ProofSubjectRegistry proofSubjects,
        SemanticControlCoordinator controls,
        RuntimeConnectionRegistry connections
    ) {
        if (bound) {
            throw new IllegalStateException("Proof execution coordinator is already bound");
        }
        this.proofSubjects = Objects.requireNonNull(
            proofSubjects,
            "proofSubjects must not be null"
        );
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        bound = true;
    }

    ProofPrerequisite prerequisite(ProofPrerequisiteStatus status, Throwable failure) {
        status = Objects.requireNonNull(status, "status must not be null");
        if ((status == ProofPrerequisiteStatus.FAILED) != (failure != null)) {
            throw new IllegalArgumentException(
                "Only a FAILED proof prerequisite requires a failure"
            );
        }
        return new RuntimeProofPrerequisite(
            prerequisiteOwner,
            status,
            failure == null ? Optional.empty() : Optional.of(FailureDetails.from(failure))
        );
    }

    ProofExecution activate(ProofPlan plan, Runnable refreshObservation) {
        plan = Objects.requireNonNull(plan, "plan must not be null");
        refreshObservation = Objects.requireNonNull(
            refreshObservation,
            "refreshObservation must not be null"
        );
        ExecutionRecord record;
        synchronized (this) {
            requireBound();
            if (closed) {
                throw new IllegalStateException(
                    "Environment execution is complete and cannot activate a proof plan"
                );
            }
            if (execution != null) {
                throw new ProofConfigurationException(
                    "An environment execution accepts exactly one proof execution"
                );
            }
            record = new ExecutionRecord(plan, this, refreshObservation);
            execution = record;
            record.state = ProofExecutionState.ACTIVATING;
        }

        ActivationControls activationControls;
        try {
            activationControls = validateStaticPlan(record);
        } catch (ProofConfigurationException failure) {
            discardInvalidExecution(record);
            throw failure;
        } catch (IllegalArgumentException failure) {
            discardInvalidExecution(record);
            throw new ProofConfigurationException(
                "Proof plan '" + plan.id()
                    + "' is incompatible with this environment execution"
            );
        } catch (RuntimeException | Error failure) {
            complete(
                record,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.ACTIVATION,
                    FailureDetails.from(failure)
                )
            );
            return record.handle;
        }

        try {
            seedPrerequisites(record);
        } catch (RuntimeException | Error failure) {
            completeAndCancelPrepared(
                record,
                activationControls,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.ACTIVATION,
                    FailureDetails.from(failure)
                )
            );
            return record.handle;
        }
        RequirementState unsupported = first(record, ProofResolution.UNSUPPORTED);
        if (unsupported != null) {
            completeAndCancelPrepared(
                record,
                activationControls,
                ProofOutcome.INCONCLUSIVE,
                null
            );
            return record.handle;
        }
        RequirementState failedPrerequisite = first(record, ProofResolution.FAILED);
        if (failedPrerequisite != null) {
            completeAndCancelPrepared(
                record,
                activationControls,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.ACTIVATION,
                    prerequisiteFailure(failedPrerequisite.requirement)
                )
            );
            return record.handle;
        }

        try {
            refreshObservation.run();
        } catch (RuntimeException | Error failure) {
            completeAndCancelPrepared(
                record,
                activationControls,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.OBSERVATION,
                    FailureDetails.from(failure)
                )
            );
            return record.handle;
        }
        if (isComplete(record)) {
            cancelPreparedAfterTerminal(record, activationControls);
            return record.handle;
        }

        RequirementState unavailableObservation;
        try {
            unavailableObservation = seedObservations(record);
        } catch (RuntimeException | Error failure) {
            completeAndCancelPrepared(
                record,
                activationControls,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.OBSERVATION,
                    FailureDetails.from(failure)
                )
            );
            return record.handle;
        }
        if (unavailableObservation != null) {
            if (unavailableObservation.resolution == ProofResolution.FAILED) {
                completeAndCancelPrepared(
                    record,
                    activationControls,
                    ProofOutcome.ERROR,
                    diagnostic(ProofFailureStage.OBSERVATION, new ObservationFailure())
                );
            } else {
                completeAndCancelPrepared(
                    record,
                    activationControls,
                    ProofOutcome.INCONCLUSIVE,
                    null
                );
            }
            return record.handle;
        }

        try {
            controls.activatePreparedControls(
                activationControls.holds,
                activationControls.guards
            );
        } catch (RuntimeException | Error failure) {
            complete(
                record,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.ACTIVATION,
                    FailureDetails.from(failure)
                )
            );
            cancelPreparedAfterTerminal(record, activationControls);
            return record.handle;
        }
        if (isComplete(record)) {
            cancelPreparedAfterTerminal(record, activationControls);
            return record.handle;
        }

        try {
            requireControlsArmed(activationControls);
            synchronized (this) {
                if (record.state != ProofExecutionState.ACTIVATING) {
                    return record.handle;
                }
                seedActiveObligations(record, activationControls);
                record.activationControls = activationControls;
                record.state = ProofExecutionState.ACTIVE;
                record.deadlineTask = deadlineScheduler.schedule(
                    record.plan.deadline(),
                    () -> deadlineExpired(record)
                );
            }
        } catch (RuntimeException | Error failure) {
            complete(
                record,
                ProofOutcome.ERROR,
                new ProofDiagnostic(
                    ProofFailureStage.ACTIVATION,
                    FailureDetails.from(failure)
                )
            );
            cancelPreparedAfterTerminal(record, activationControls);
        }
        return record.handle;
    }

    @Override
    public void fact(ScenarioEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        ExecutionRecord toCancel = null;
        try {
            synchronized (this) {
                if (execution == null) {
                    return;
                }
                if (execution.state == ProofExecutionState.COMPLETED) {
                    retainSecondary(execution, event);
                    return;
                }
                if (execution.state == ProofExecutionState.ACTIVATING) {
                    if (event instanceof FailureEvent failure) {
                        completeLocked(
                            execution,
                            ProofOutcome.ERROR,
                            new ProofDiagnostic(failureStage(failure), failure.failure())
                        );
                    }
                    return;
                }
                if (execution.state != ProofExecutionState.ACTIVE) {
                    return;
                }
                ProofOutcome before = execution.outcome;
                switch (event) {
                    case CorrelationCandidateEvent candidate ->
                        applyCorrelation(execution, candidate);
                    case SemanticHoldEvent hold -> applyHold(execution, hold);
                    case SemanticPredecessorGuardEvent guard -> applyGuard(execution, guard);
                    case FailureEvent failure -> completeLocked(
                        execution,
                        ProofOutcome.ERROR,
                        new ProofDiagnostic(failureStage(failure), failure.failure())
                    );
                    default -> {
                        // Unrelated typed journal facts do not affect proof current state.
                    }
                }
                if (before == null && execution.outcome != null) {
                    toCancel = execution;
                }
            }
        } catch (RuntimeException | Error evaluatorFailure) {
            synchronized (this) {
                if (execution != null && execution.state != ProofExecutionState.COMPLETED) {
                    completeLocked(
                        execution,
                        ProofOutcome.ERROR,
                        new ProofDiagnostic(
                            ProofFailureStage.EVALUATION,
                            FailureDetails.from(evaluatorFailure)
                        )
                    );
                    toCancel = execution;
                }
            }
        }
        cancelAfterTerminal(toCancel);
    }

    @Override
    public void journalFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        ExecutionRecord toCancel = null;
        synchronized (this) {
            if (execution == null) {
                return;
            }
            if (execution.state == ProofExecutionState.COMPLETED) {
                addSecondary(
                    execution,
                    new ProofDiagnostic(ProofFailureStage.JOURNAL, FailureDetails.from(failure))
                );
                return;
            }
            completeLocked(
                execution,
                ProofOutcome.ERROR,
                new ProofDiagnostic(ProofFailureStage.JOURNAL, FailureDetails.from(failure))
            );
            toCancel = execution;
        }
        cancelAfterTerminal(toCancel);
    }

    @Override
    public void observationChanged(RuntimeConnectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        ExecutionRecord toCancel = null;
        synchronized (this) {
            if (execution == null) {
                return;
            }
            if (execution.state == ProofExecutionState.COMPLETED) {
                if (observationState(execution, snapshot.id()) != null
                    && snapshot.effectiveObservationStatus()
                    != EffectiveObservationStatus.ACTIVE) {
                    addSecondary(
                        execution,
                        diagnostic(
                            ProofFailureStage.OBSERVATION,
                            new ObservationFailure()
                        )
                    );
                }
                return;
            }
            if (execution.state != ProofExecutionState.ACTIVE) {
                return;
            }
            RequirementState observation = execution.observations.get(snapshot.id());
            if (observation == null || snapshot.effectiveObservationStatus()
                == EffectiveObservationStatus.ACTIVE) {
                return;
            }
            switch (snapshot.effectiveObservationStatus()) {
                case FAILED, DISABLED, PENDING -> {
                    observation.set(
                        ProofResolution.FAILED,
                        ProofResolutionReason.OBSERVATION_FAILED,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    completeLocked(
                        execution,
                        ProofOutcome.ERROR,
                        diagnostic(ProofFailureStage.OBSERVATION, new ObservationFailure())
                    );
                }
                case UNSUPPORTED -> {
                    observation.set(
                        ProofResolution.UNSUPPORTED,
                        ProofResolutionReason.OBSERVATION_UNSUPPORTED,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    completeLocked(execution, ProofOutcome.INCONCLUSIVE, null);
                }
                case DEGRADED, INACTIVE -> {
                    observation.set(
                        ProofResolution.MISSING,
                        ProofResolutionReason.OBSERVATION_LOST,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    completeLocked(execution, ProofOutcome.INCONCLUSIVE, null);
                }
                case ACTIVE -> throw new IllegalStateException("ACTIVE was handled earlier");
            }
            toCancel = execution;
        }
        cancelAfterTerminal(toCancel);
    }

    @Override
    public void requiredObservationFailed(ConnectionId connectionId) {
        connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        ExecutionRecord toCancel = null;
        synchronized (this) {
            failedRequiredObservations.add(connectionId);
            if (execution == null) {
                return;
            }
            if (execution.state == ProofExecutionState.COMPLETED) {
                if (observationState(execution, connectionId) != null) {
                    addSecondary(
                        execution,
                        diagnostic(
                            ProofFailureStage.OBSERVATION,
                            new ObservationFailure()
                        )
                    );
                }
                return;
            }
            RequirementState observation = observationState(
                execution,
                connectionId
            );
            if (observation == null) {
                return;
            }
            observation.set(
                ProofResolution.FAILED,
                ProofResolutionReason.OBSERVATION_FAILED,
                Optional.of(connectionId),
                List.of()
            );
            completeLocked(
                execution,
                ProofOutcome.ERROR,
                diagnostic(
                    ProofFailureStage.OBSERVATION,
                    new ObservationFailure()
                )
            );
            toCancel = execution;
        }
        cancelAfterTerminal(toCancel);
    }

    Throwable completeExecution() {
        ExecutionRecord toCancel = null;
        Throwable unfinished = null;
        synchronized (this) {
            if (closed) {
                return null;
            }
            closed = true;
            if (execution != null && execution.state != ProofExecutionState.COMPLETED) {
                completeLocked(
                    execution,
                    ProofOutcome.ERROR,
                    diagnostic(ProofFailureStage.TEARDOWN, new UnfinishedProofExecution())
                );
                toCancel = execution;
                unfinished = new IllegalStateException(
                    "Environment closed with an unfinished active proof execution"
                );
            }
        }
        cancelAfterTerminal(toCancel);
        deadlineScheduler.close();
        return unfinished;
    }

    private ActivationControls validateStaticPlan(ExecutionRecord record) {
        proofSubjects.validateSubject(record.plan.primarySubject());
        Map<ConnectionId, ProofPlan.Observation> observations = new LinkedHashMap<>();
        List<SemanticHoldRef> holds = new ArrayList<>();
        List<ConnectionId> holdConnections = new ArrayList<>();
        List<SemanticPredecessorGuardRef> guards = new ArrayList<>();

        for (ProofPlan.Requirement requirement : record.plan.requirements()) {
            switch (requirement) {
                case ProofPlan.Prerequisite prerequisite ->
                    requirePrerequisite(prerequisite.prerequisite());
                case ProofPlan.Observation observation -> {
                    connections.validateProofObservation(
                        observation.connectionId(),
                        observation.profile()
                    );
                    observations.put(observation.connectionId(), observation);
                }
                case ProofPlan.Correlation correlation -> {
                    proofSubjects.validateSubjectFlow(
                        record.plan.primarySubject(),
                        correlation.key()
                    );
                    connections.validateProofCorrelation(
                        correlation.connectionId(),
                        correlation.nativeReferenceSchema()
                    );
                }
                case ProofPlan.HoldControl control -> {
                    SemanticControlCoordinator.HoldDeclaration declaration =
                        controls.holdDeclaration(control.holdRef());
                    if (declaration.state() != SemanticHoldState.DECLARED
                        || declaration.proofSubject()
                            .filter(record.plan.primarySubject()::equals).isEmpty()) {
                        throw new ProofConfigurationException(
                            "Required semantic hold must be DECLARED for the primary subject"
                        );
                    }
                    holds.add(control.holdRef());
                    holdConnections.add(declaration.connectionId());
                }
                case ProofPlan.GuardControl control -> {
                    SemanticControlCoordinator.GuardDeclaration declaration =
                        controls.guardDeclaration(control.guardRef());
                    if (declaration.state() != SemanticPredecessorGuardState.DECLARED
                        || !declaration.subject().equals(record.plan.primarySubject())) {
                        throw new ProofConfigurationException(
                            "Required predecessor guard must be DECLARED for the primary subject"
                        );
                    }
                    guards.add(control.guardRef());
                }
                case ProofPlan.HoldEvidence evidence -> controls.holdDeclaration(
                    evidence.holdRef()
                );
                case ProofPlan.GuardEvidence evidence -> controls.guardDeclaration(
                    evidence.guardRef()
                );
                case ProofPlan.CausalRelation relation -> controls.guardDeclaration(
                    relation.guardRef()
                );
            }
        }

        for (ProofPlan.Requirement requirement : record.plan.requirements()) {
            switch (requirement) {
                case ProofPlan.Correlation correlation -> requireObservation(
                    observations,
                    correlation.connectionId(),
                    correlation.id().toString()
                );
                case ProofPlan.HoldControl control -> requireObservation(
                    observations,
                    controls.holdDeclaration(control.holdRef()).connectionId(),
                    control.id().toString()
                );
                case ProofPlan.GuardControl control -> {
                    SemanticControlCoordinator.GuardDeclaration declaration =
                        controls.guardDeclaration(control.guardRef());
                    requireObservation(
                        observations,
                        declaration.predecessorConnectionId(),
                        control.id().toString()
                    );
                    requireObservation(
                        observations,
                        declaration.successorConnectionId(),
                        control.id().toString()
                    );
                }
                default -> {
                    // Other requirements refer to already validated controls or prerequisites.
                }
            }
        }
        return new ActivationControls(
            List.copyOf(holds),
            List.copyOf(holdConnections),
            List.copyOf(guards)
        );
    }

    private void seedPrerequisites(ExecutionRecord record) {
        synchronized (this) {
            for (RequirementState state : record.states) {
                if (!(state.requirement instanceof ProofPlan.Prerequisite requirement)) {
                    continue;
                }
                RuntimeProofPrerequisite prerequisite = requirePrerequisite(
                    requirement.prerequisite()
                );
                switch (prerequisite.status) {
                    case SATISFIED -> state.set(
                        ProofResolution.SATISFIED,
                        ProofResolutionReason.PREREQUISITE_SATISFIED,
                        Optional.empty(),
                        List.of()
                    );
                    case UNSUPPORTED -> state.set(
                        ProofResolution.UNSUPPORTED,
                        ProofResolutionReason.PREREQUISITE_UNSUPPORTED,
                        Optional.empty(),
                        List.of()
                    );
                    case FAILED -> state.set(
                        ProofResolution.FAILED,
                        ProofResolutionReason.PREREQUISITE_FAILED,
                        Optional.empty(),
                        List.of()
                    );
                }
            }
        }
    }

    private RequirementState seedObservations(ExecutionRecord record) {
        List<ObservationSeed> seeds = new ArrayList<>();
        for (RequirementState state : record.states) {
            if (state.requirement instanceof ProofPlan.Observation observation) {
                seeds.add(new ObservationSeed(
                    state,
                    connections.snapshot(observation.connectionId())
                ));
            }
        }
        synchronized (this) {
            for (ObservationSeed seed : seeds) {
                RequirementState state = seed.state();
                RuntimeConnectionSnapshot snapshot = seed.snapshot();
                if (failedRequiredObservations.contains(snapshot.id())) {
                    state.set(
                        ProofResolution.FAILED,
                        ProofResolutionReason.OBSERVATION_FAILED,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    record.observations.put(snapshot.id(), state);
                    return state;
                }
                switch (snapshot.effectiveObservationStatus()) {
                    case ACTIVE -> state.set(
                        ProofResolution.SATISFIED,
                        ProofResolutionReason.OBSERVATION_ACTIVE,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    case UNSUPPORTED -> state.set(
                        ProofResolution.UNSUPPORTED,
                        ProofResolutionReason.OBSERVATION_UNSUPPORTED,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    case DEGRADED, INACTIVE -> state.set(
                        ProofResolution.MISSING,
                        ProofResolutionReason.OBSERVATION_LOST,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                    case DISABLED, PENDING, FAILED -> state.set(
                        ProofResolution.FAILED,
                        ProofResolutionReason.OBSERVATION_FAILED,
                        Optional.of(snapshot.id()),
                        List.of()
                    );
                }
                record.observations.put(snapshot.id(), state);
                if (state.resolution != ProofResolution.SATISFIED) {
                    return state;
                }
            }
            return null;
        }
    }

    private void seedActiveObligations(
        ExecutionRecord record,
        ActivationControls activationControls
    ) {
        for (RequirementState state : record.states) {
            switch (state.requirement) {
                case ProofPlan.Prerequisite ignored -> {
                    // Seeded before the evidence window.
                }
                case ProofPlan.Observation ignored -> {
                    // Seeded before the evidence window.
                }
                case ProofPlan.Correlation correlation -> {
                    state.set(
                        ProofResolution.MISSING,
                        ProofResolutionReason.CORRELATION_MISSING,
                        Optional.of(correlation.connectionId()),
                        List.of()
                    );
                    record.correlations.add(state);
                }
                case ProofPlan.HoldControl control -> {
                    state.set(
                        ProofResolution.UNREACHED,
                        ProofResolutionReason.CONTROL_UNREACHED,
                        Optional.of(activationControls.connectionFor(control.holdRef())),
                        List.of()
                    );
                    record.holdControls.put(control.holdRef(), state);
                }
                case ProofPlan.GuardControl control -> {
                    state.set(
                        ProofResolution.UNREACHED,
                        ProofResolutionReason.CONTROL_UNREACHED,
                        Optional.empty(),
                        List.of()
                    );
                    record.guardControls.put(control.guardRef(), state);
                }
                case ProofPlan.HoldEvidence evidence -> {
                    state.set(
                        ProofResolution.MISSING,
                        ProofResolutionReason.EVIDENCE_MISSING,
                        Optional.empty(),
                        List.of()
                    );
                    record.holdEvidence.put(evidence.holdRef(), state);
                }
                case ProofPlan.GuardEvidence evidence -> {
                    state.set(
                        ProofResolution.MISSING,
                        ProofResolutionReason.EVIDENCE_MISSING,
                        Optional.empty(),
                        List.of()
                    );
                    record.guardEvidence.computeIfAbsent(
                        evidence.guardRef(),
                        ignored -> new HashMap<>()
                    ).put(evidence.evidenceKind(), state);
                }
                case ProofPlan.CausalRelation relation -> {
                    state.set(
                        ProofResolution.UNREACHED,
                        ProofResolutionReason.CAUSAL_RELATION_UNREACHED,
                        Optional.empty(),
                        List.of()
                    );
                    record.relations.put(relation.guardRef(), state);
                }
            }
        }
    }

    private void applyCorrelation(ExecutionRecord record, CorrelationCandidateEvent event) {
        for (RequirementState state : record.correlations) {
            ProofPlan.Correlation correlation = (ProofPlan.Correlation) state.requirement;
            if (!correlation.key().equals(event.key())
                || !correlation.connectionId().equals(event.interactionRef().connectionId())
                || !correlation.nativeReferenceSchema().equals(
                    event.nativeReference().schemaId()
                )) {
                continue;
            }
            switch (event.cardinality()) {
                case UNIQUE -> {
                    if (event.proofSubject().filter(record.plan.primarySubject()::equals).isPresent()) {
                        state.set(
                            ProofResolution.SATISFIED,
                            ProofResolutionReason.CORRELATION_UNIQUE,
                            Optional.of(correlation.connectionId()),
                            List.of(event.interactionRef())
                        );
                    }
                }
                case AMBIGUOUS -> state.set(
                    ProofResolution.AMBIGUOUS,
                    ProofResolutionReason.CORRELATION_AMBIGUOUS,
                    Optional.of(correlation.connectionId()),
                    List.of()
                );
                case MISSING -> state.set(
                    ProofResolution.MISSING,
                    ProofResolutionReason.CORRELATION_MISSING,
                    Optional.of(correlation.connectionId()),
                    List.of()
                );
            }
        }
    }

    private void applyHold(ExecutionRecord record, SemanticHoldEvent event) {
        RequirementState control = record.holdControls.get(event.holdRef());
        RequirementState evidence = record.holdEvidence.get(event.holdRef());
        if (control == null && evidence == null) {
            return;
        }
        List<InteractionRef> interactions = event.interactionRef().stream().toList();
        if (evidence != null && event.interactionRef().isPresent()) {
            evidence.set(
                ProofResolution.SATISFIED,
                ProofResolutionReason.EVIDENCE_PRESENT,
                Optional.of(event.connectionId()),
                interactions
            );
        }
        if (control == null) {
            return;
        }
        switch (event.state()) {
            case DECLARED, ARMED, REACHED_HELD, RELEASING -> {
                // Still unresolved; evidence may already be present.
            }
            case FORWARDED -> control.set(
                ProofResolution.SATISFIED,
                ProofResolutionReason.CONTROL_REACHED_EXPECTED_STATE,
                Optional.of(event.connectionId()),
                interactions
            );
            case CANCELLED -> control.set(
                ProofResolution.UNREACHED,
                ProofResolutionReason.CONTROL_UNREACHED,
                Optional.of(event.connectionId()),
                interactions
            );
            case TIMED_OUT -> {
                control.set(
                    ProofResolution.TIMED_OUT,
                    ProofResolutionReason.CONTROL_TIMED_OUT,
                    Optional.of(event.connectionId()),
                    interactions
                );
                completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            }
            case FAILED -> applyHoldFailure(record, control, event, interactions);
        }
    }

    private void applyHoldFailure(
        ExecutionRecord record,
        RequirementState control,
        SemanticHoldEvent event,
        List<InteractionRef> interactions
    ) {
        SemanticHoldFailure failure = event.failure().orElse(SemanticHoldFailure.INTERNAL_FAILURE);
        switch (failure) {
            case CORRELATION_INVALIDATED, AMBIGUOUS_MATCH -> {
                control.set(
                    ProofResolution.AMBIGUOUS,
                    ProofResolutionReason.CONTROL_CORRELATION_INVALIDATED,
                    Optional.of(event.connectionId()),
                    interactions
                );
                completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            }
            case SESSION_ABANDONED -> {
                control.set(
                    ProofResolution.MISSING,
                    ProofResolutionReason.CONTROL_SESSION_ENDED,
                    Optional.of(event.connectionId()),
                    interactions
                );
                completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            }
            case SELECTOR_EVALUATION, WRITE_FAILURE, INTERNAL_FAILURE -> {
                control.set(
                    ProofResolution.FAILED,
                    ProofResolutionReason.CONTROL_FAILED,
                    Optional.of(event.connectionId()),
                    interactions
                );
                completeLocked(
                    record,
                    ProofOutcome.ERROR,
                    diagnostic(ProofFailureStage.CONTROL, new ControlFailure())
                );
            }
        }
    }

    private void applyGuard(ExecutionRecord record, SemanticPredecessorGuardEvent event) {
        RequirementState control = record.guardControls.get(event.guardRef());
        Map<ProofEvidenceKind, RequirementState> evidence = record.guardEvidence.get(
            event.guardRef()
        );
        RequirementState relation = record.relations.get(event.guardRef());
        if (control == null && evidence == null && relation == null) {
            return;
        }
        List<InteractionRef> interactions = guardInteractions(event);
        if (evidence != null) {
            event.predecessor().ifPresent(reference -> setEvidence(
                evidence.get(ProofEvidenceKind.PREDECESSOR_INTERACTION),
                reference
            ));
            event.successor().ifPresent(reference -> setEvidence(
                evidence.get(ProofEvidenceKind.SUCCESSOR_INTERACTION),
                reference
            ));
        }
        if (event.kind() == SemanticPredecessorGuardEvent.Kind.RELATION && relation != null) {
            relation.set(
                ProofResolution.SATISFIED,
                ProofResolutionReason.CAUSAL_RELATION_ESTABLISHED,
                Optional.empty(),
                interactions
            );
        }
        if (event.kind() == SemanticPredecessorGuardEvent.Kind.VIOLATION) {
            if (control != null) {
                control.set(
                    ProofResolution.VIOLATED,
                    ProofResolutionReason.CAUSAL_RELATION_VIOLATED,
                    Optional.empty(),
                    interactions
                );
            }
            if (relation != null) {
                relation.set(
                    ProofResolution.VIOLATED,
                    ProofResolutionReason.CAUSAL_RELATION_VIOLATED,
                    Optional.empty(),
                    interactions
                );
            }
            completeLocked(record, ProofOutcome.VIOLATED, null);
            return;
        }
        if (control == null || event.kind() != SemanticPredecessorGuardEvent.Kind.STATE) {
            return;
        }
        switch (event.state()) {
            case DECLARED, ARMED, PREDECESSOR_OBSERVED, PREDECESSOR_SATISFIED,
                 SUCCESSOR_AUTHORIZED -> {
                // Still unresolved.
            }
            case SATISFIED -> control.set(
                ProofResolution.SATISFIED,
                ProofResolutionReason.CONTROL_REACHED_EXPECTED_STATE,
                Optional.empty(),
                interactions
            );
            case VIOLATED -> {
                // The explicit VIOLATION fact is the authoritative terminal counterexample.
            }
            case CANCELLED -> control.set(
                ProofResolution.UNREACHED,
                ProofResolutionReason.CONTROL_UNREACHED,
                Optional.empty(),
                interactions
            );
            case TIMED_OUT -> {
                control.set(
                    ProofResolution.TIMED_OUT,
                    ProofResolutionReason.CONTROL_TIMED_OUT,
                    Optional.empty(),
                    interactions
                );
                completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            }
            case FAILED -> applyGuardFailure(record, control, event, interactions);
        }
    }

    private void applyGuardFailure(
        ExecutionRecord record,
        RequirementState control,
        SemanticPredecessorGuardEvent event,
        List<InteractionRef> interactions
    ) {
        SemanticPredecessorGuardFailure failure = event.failure()
            .orElse(SemanticPredecessorGuardFailure.INTERNAL_FAILURE);
        switch (failure) {
            case CORRELATION_INVALIDATED -> {
                control.set(
                    ProofResolution.AMBIGUOUS,
                    ProofResolutionReason.CONTROL_CORRELATION_INVALIDATED,
                    Optional.empty(),
                    interactions
                );
                completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            }
            case SESSION_ABANDONED -> {
                control.set(
                    ProofResolution.MISSING,
                    ProofResolutionReason.CONTROL_SESSION_ENDED,
                    Optional.empty(),
                    interactions
                );
                completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            }
            case SELECTOR_EVALUATION, WRITE_FAILURE, REQUIRED_OBSERVATION_FAILURE,
                 INTERNAL_FAILURE -> {
                control.set(
                    ProofResolution.FAILED,
                    ProofResolutionReason.CONTROL_FAILED,
                    Optional.empty(),
                    interactions
                );
                completeLocked(
                    record,
                    ProofOutcome.ERROR,
                    diagnostic(ProofFailureStage.CONTROL, new ControlFailure())
                );
            }
        }
    }

    private void deadlineExpired(ExecutionRecord record) {
        ExecutionRecord toCancel = null;
        synchronized (this) {
            if (record != execution || record.state != ProofExecutionState.ACTIVE) {
                return;
            }
            RequirementState unresolved = record.states.stream()
                .filter(value -> value.resolution != ProofResolution.SATISFIED)
                .findFirst()
                .orElse(null);
            if (unresolved != null) {
                unresolved.set(
                    ProofResolution.TIMED_OUT,
                    ProofResolutionReason.DEADLINE_EXPIRED,
                    unresolved.connectionId,
                    unresolved.interactions
                );
            }
            completeLocked(record, ProofOutcome.INCONCLUSIVE, null);
            toCancel = record;
        }
        cancelAfterTerminal(toCancel);
    }

    private void runStimulus(ExecutionRecord record, Runnable stimulus) {
        stimulus = Objects.requireNonNull(stimulus, "stimulus must not be null");
        synchronized (this) {
            requireRecord(record);
            if (record.stimulusAttempted) {
                throw new IllegalStateException("Proof stimulus can be attempted only once");
            }
            record.stimulusAttempted = true;
            if (record.state == ProofExecutionState.COMPLETED) {
                return;
            }
            if (record.state != ProofExecutionState.ACTIVE) {
                throw new IllegalStateException(
                    "Proof stimulus requires an ACTIVE execution"
                );
            }
        }
        try {
            stimulus.run();
        } catch (RuntimeException | Error failure) {
            ExecutionRecord toCancel;
            synchronized (this) {
                if (record.state != ProofExecutionState.COMPLETED) {
                    completeLocked(
                        record,
                        ProofOutcome.ERROR,
                        new ProofDiagnostic(
                            ProofFailureStage.STIMULUS,
                            FailureDetails.from(failure)
                        )
                    );
                }
                toCancel = record;
            }
            cancelAfterTerminal(toCancel);
        }
    }

    private ProofResult evaluate(ExecutionRecord record) {
        boolean refresh;
        synchronized (this) {
            requireRecord(record);
            refresh = record.state == ProofExecutionState.ACTIVE;
        }
        if (refresh) {
            try {
                record.refreshObservation.run();
            } catch (RuntimeException | Error failure) {
                complete(
                    record,
                    ProofOutcome.ERROR,
                    new ProofDiagnostic(
                        ProofFailureStage.OBSERVATION,
                        FailureDetails.from(failure)
                    )
                );
            }
        }
        ExecutionRecord toCancel = null;
        ProofResult result;
        synchronized (this) {
            requireRecord(record);
            if (record.result != null) {
                return record.result;
            }
            if (record.state == ProofExecutionState.ACTIVE) {
                record.state = ProofExecutionState.EVALUATING;
                try {
                    ProofOutcome outcome = outcomeEvaluator.evaluate(
                        record.states.stream().map(value -> value.resolution).toList()
                    );
                    if (outcome == ProofOutcome.ERROR && record.primaryFailure == null) {
                        record.primaryFailure = diagnostic(
                            ProofFailureStage.EVALUATION,
                            new EvaluationFailure()
                        );
                    }
                    completeLocked(record, outcome, record.primaryFailure);
                } catch (RuntimeException | Error evaluatorFailure) {
                    completeLocked(
                        record,
                        ProofOutcome.ERROR,
                        new ProofDiagnostic(
                            ProofFailureStage.EVALUATION,
                            FailureDetails.from(evaluatorFailure)
                        )
                    );
                }
                toCancel = record;
            }
            if (record.state != ProofExecutionState.COMPLETED) {
                throw new IllegalStateException(
                    "Proof execution cannot be evaluated from state " + record.state
                );
            }
            result = materializeResult(record);
        }
        cancelAfterTerminal(toCancel);
        return result;
    }

    private ProofResult result(ExecutionRecord record) {
        synchronized (this) {
            requireRecord(record);
            if (record.state != ProofExecutionState.COMPLETED) {
                throw new IllegalStateException(
                    "Proof result is unavailable before execution completion"
                );
            }
            return materializeResult(record);
        }
    }

    private void complete(
        ExecutionRecord record,
        ProofOutcome outcome,
        ProofDiagnostic failure
    ) {
        ExecutionRecord toCancel = null;
        synchronized (this) {
            if (record.state != ProofExecutionState.COMPLETED) {
                completeLocked(record, outcome, failure);
                toCancel = record;
            }
        }
        cancelAfterTerminal(toCancel);
    }

    private void completeAndCancelPrepared(
        ExecutionRecord record,
        ActivationControls activationControls,
        ProofOutcome outcome,
        ProofDiagnostic failure
    ) {
        complete(record, outcome, failure);
        cancelPreparedAfterTerminal(record, activationControls);
    }

    private void cancelPreparedAfterTerminal(
        ExecutionRecord record,
        ActivationControls activationControls
    ) {
        try {
            controls.cancelPreparedControls(
                activationControls.holds,
                activationControls.guards
            );
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                addSecondary(
                    record,
                    diagnostic(ProofFailureStage.CLEANUP, failure)
                );
            }
        }
    }

    private void completeLocked(
        ExecutionRecord record,
        ProofOutcome outcome,
        ProofDiagnostic failure
    ) {
        if (record.state == ProofExecutionState.COMPLETED) {
            if (failure != null) {
                addSecondary(record, failure);
            }
            return;
        }
        record.state = ProofExecutionState.EVALUATING;
        record.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        record.primaryFailure = failure;
        if (outcome == ProofOutcome.VIOLATED || outcome == ProofOutcome.ERROR) {
            markNotEvaluatedAfterTerminal(record);
        } else if (outcome == ProofOutcome.INCONCLUSIVE) {
            markActivationNotReached(record);
        }
        cancelDeadline(record);
        record.state = ProofExecutionState.COMPLETED;
    }

    private static void markNotEvaluatedAfterTerminal(ExecutionRecord record) {
        boolean decisiveFound = false;
        for (RequirementState state : record.states) {
            boolean decisive = record.outcome == ProofOutcome.VIOLATED
                ? state.resolution == ProofResolution.VIOLATED
                : state.resolution == ProofResolution.FAILED;
            if (decisive) {
                decisiveFound = true;
                continue;
            }
            if (!decisiveFound && state.resolution == ProofResolution.SATISFIED) {
                continue;
            }
            if (state.resolution != ProofResolution.VIOLATED
                && state.resolution != ProofResolution.FAILED) {
                state.set(
                    ProofResolution.NOT_EVALUATED,
                    ProofResolutionReason.NOT_EVALUATED_AFTER_TERMINAL_OUTCOME,
                    state.connectionId,
                    List.of()
                );
            }
        }
    }

    private static void markActivationNotReached(ExecutionRecord record) {
        for (RequirementState state : record.states) {
            if (state.resolution == ProofResolution.NOT_EVALUATED) {
                state.set(
                    ProofResolution.UNREACHED,
                    ProofResolutionReason.ACTIVATION_NOT_REACHED,
                    state.connectionId,
                    List.of()
                );
            }
        }
    }

    private ProofResult materializeResult(ExecutionRecord record) {
        if (record.result == null) {
            List<ProofObligationResolution> resolutions = record.states.stream()
                .map(RequirementState::snapshot)
                .toList();
            record.result = new ProofResult(
                record.plan.id(),
                record.plan.title(),
                record.outcome,
                record.plan.primarySubject(),
                resolutions,
                Optional.ofNullable(record.primaryFailure),
                record.secondaryDiagnostics
            );
        }
        return record.result;
    }

    private void requireControlsArmed(ActivationControls activationControls) {
        for (SemanticHoldRef hold : activationControls.holds) {
            if (controls.holdDeclaration(hold).state() != SemanticHoldState.ARMED) {
                throw new IllegalStateException(
                    "Prepared semantic hold did not reach ARMED during proof activation"
                );
            }
        }
        for (SemanticPredecessorGuardRef guard : activationControls.guards) {
            if (controls.guardDeclaration(guard).state()
                != SemanticPredecessorGuardState.ARMED) {
                throw new IllegalStateException(
                    "Prepared predecessor guard did not reach ARMED during proof activation"
                );
            }
        }
    }

    private RuntimeProofPrerequisite requirePrerequisite(ProofPrerequisite prerequisite) {
        Objects.requireNonNull(prerequisite, "prerequisite must not be null");
        if (!(prerequisite instanceof RuntimeProofPrerequisite runtime)
            || runtime.owner != prerequisiteOwner) {
            throw new ProofConfigurationException(
                "Proof prerequisite belongs to a different environment execution"
            );
        }
        return runtime;
    }

    private FailureDetails prerequisiteFailure(ProofPlan.Requirement requirement) {
        ProofPlan.Prerequisite prerequisite = (ProofPlan.Prerequisite) requirement;
        return requirePrerequisite(prerequisite.prerequisite()).failure.orElseThrow();
    }

    private static void requireObservation(
        Map<ConnectionId, ProofPlan.Observation> observations,
        ConnectionId connectionId,
        String obligationId
    ) {
        if (!observations.containsKey(connectionId)) {
            throw new ProofConfigurationException(
                "Proof obligation '" + obligationId
                    + "' has no required observation coverage declaration"
            );
        }
    }

    private void discardInvalidExecution(ExecutionRecord record) {
        synchronized (this) {
            if (execution == record && record.state == ProofExecutionState.ACTIVATING) {
                execution = null;
            }
        }
    }

    private synchronized boolean isComplete(ExecutionRecord record) {
        return record.state == ProofExecutionState.COMPLETED;
    }

    private void cancelAfterTerminal(ExecutionRecord record) {
        if (record == null || record.activationControls == null) {
            return;
        }
        controls.cancelPreparedControls(
            record.activationControls.holds,
            record.activationControls.guards
        );
    }

    private static void setEvidence(RequirementState state, InteractionRef reference) {
        if (state != null) {
            state.set(
                ProofResolution.SATISFIED,
                ProofResolutionReason.EVIDENCE_PRESENT,
                Optional.of(reference.connectionId()),
                List.of(reference)
            );
        }
    }

    private static List<InteractionRef> guardInteractions(
        SemanticPredecessorGuardEvent event
    ) {
        List<InteractionRef> interactions = new ArrayList<>(2);
        event.predecessor().ifPresent(interactions::add);
        event.successor().ifPresent(interactions::add);
        return List.copyOf(interactions);
    }

    private static RequirementState first(ExecutionRecord record, ProofResolution resolution) {
        return record.states.stream()
            .filter(value -> value.resolution == resolution)
            .findFirst()
            .orElse(null);
    }

    private static RequirementState observationState(
        ExecutionRecord record,
        ConnectionId connectionId
    ) {
        RequirementState indexed = record.observations.get(connectionId);
        if (indexed != null) {
            return indexed;
        }
        return record.states.stream()
            .filter(state -> state.requirement instanceof ProofPlan.Observation observation
                && observation.connectionId().equals(connectionId))
            .findFirst()
            .orElse(null);
    }

    private static ProofDiagnostic diagnostic(
        ProofFailureStage stage,
        Throwable failure
    ) {
        return new ProofDiagnostic(stage, FailureDetails.from(failure));
    }

    private static ProofFailureStage failureStage(FailureEvent failure) {
        return switch (failure) {
            case FailureEvent.ConnectionCleanup ignored -> ProofFailureStage.CLEANUP;
            case FailureEvent.ComponentCleanup ignored -> ProofFailureStage.CLEANUP;
            case FailureEvent.DriverResourceCleanup ignored -> ProofFailureStage.CLEANUP;
            case FailureEvent.ConnectionMaterialization ignored -> ProofFailureStage.GATEWAY;
            case FailureEvent.EnvironmentStartup ignored -> ProofFailureStage.ACTIVATION;
            case FailureEvent.ComponentStartup ignored -> ProofFailureStage.ACTIVATION;
        };
    }

    private static void retainSecondary(ExecutionRecord record, ScenarioEvent event) {
        if (event instanceof FailureEvent failure) {
            addSecondary(
                record,
                new ProofDiagnostic(failureStage(failure), failure.failure())
            );
        } else if (event instanceof SemanticPredecessorGuardEvent guard
            && guard.kind() == SemanticPredecessorGuardEvent.Kind.SUPPRESSED_FAILURE) {
            addSecondary(
                record,
                diagnostic(ProofFailureStage.CLEANUP, new ControlFailure())
            );
        }
    }

    private static void addSecondary(ExecutionRecord record, ProofDiagnostic diagnostic) {
        if (record.secondaryDiagnostics.size() < MAX_SECONDARY_DIAGNOSTICS) {
            record.secondaryDiagnostics.add(Objects.requireNonNull(
                diagnostic,
                "diagnostic must not be null"
            ));
        }
    }

    private static void cancelDeadline(ExecutionRecord record) {
        if (record.deadlineTask != null) {
            record.deadlineTask.cancel();
            record.deadlineTask = null;
        }
    }

    private synchronized void requireRecord(ExecutionRecord record) {
        if (record != execution) {
            throw new IllegalArgumentException(
                "Proof execution belongs to a different environment execution"
            );
        }
    }

    private void requireBound() {
        if (!bound) {
            throw new IllegalStateException("Proof execution coordinator is not bound");
        }
    }

    interface DeadlineScheduler extends AutoCloseable {
        DeadlineTask schedule(Duration delay, Runnable action);

        @Override
        void close();
    }

    @FunctionalInterface
    interface DeadlineTask {
        void cancel();
    }

    private static final class SystemDeadlineScheduler implements DeadlineScheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "system-proof-deadline");
                thread.setDaemon(true);
                return thread;
            }
        );

        @Override
        public DeadlineTask schedule(Duration delay, Runnable action) {
            ScheduledFuture<?> future = executor.schedule(
                Objects.requireNonNull(action, "action must not be null"),
                Objects.requireNonNull(delay, "delay must not be null").toNanos(),
                TimeUnit.NANOSECONDS
            );
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private record ActivationControls(
        List<SemanticHoldRef> holds,
        List<ConnectionId> holdConnections,
        List<SemanticPredecessorGuardRef> guards
    ) {
        private ActivationControls {
            holds = List.copyOf(Objects.requireNonNull(holds, "holds must not be null"));
            holdConnections = List.copyOf(Objects.requireNonNull(
                holdConnections,
                "holdConnections must not be null"
            ));
            guards = List.copyOf(Objects.requireNonNull(guards, "guards must not be null"));
            if (holds.size() != holdConnections.size()) {
                throw new IllegalArgumentException(
                    "Every activated hold requires one owning connection"
                );
            }
        }

        private ConnectionId connectionFor(SemanticHoldRef holdRef) {
            for (int index = 0; index < holds.size(); index++) {
                if (holds.get(index) == holdRef) {
                    return holdConnections.get(index);
                }
            }
            throw new IllegalStateException("Activated hold connection is unavailable");
        }
    }

    private record ObservationSeed(
        RequirementState state,
        RuntimeConnectionSnapshot snapshot
    ) {
        private ObservationSeed {
            state = Objects.requireNonNull(state, "state must not be null");
            snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    private static final class ExecutionRecord {
        private final ProofPlan plan;
        private final ExecutionHandle handle;
        private final Runnable refreshObservation;
        private final List<RequirementState> states;
        private final Map<ConnectionId, RequirementState> observations = new HashMap<>();
        private final List<RequirementState> correlations = new ArrayList<>();
        private final Map<SemanticHoldRef, RequirementState> holdControls = new HashMap<>();
        private final Map<SemanticPredecessorGuardRef, RequirementState> guardControls =
            new HashMap<>();
        private final Map<SemanticHoldRef, RequirementState> holdEvidence = new HashMap<>();
        private final Map<
            SemanticPredecessorGuardRef,
            Map<ProofEvidenceKind, RequirementState>
        > guardEvidence = new HashMap<>();
        private final Map<SemanticPredecessorGuardRef, RequirementState> relations =
            new HashMap<>();
        private final List<ProofDiagnostic> secondaryDiagnostics = new ArrayList<>();
        private ProofExecutionState state = ProofExecutionState.DRAFT;
        private ProofOutcome outcome;
        private ProofDiagnostic primaryFailure;
        private ProofResult result;
        private DeadlineTask deadlineTask;
        private ActivationControls activationControls;
        private boolean stimulusAttempted;

        private ExecutionRecord(
            ProofPlan plan,
            ProofExecutionCoordinator coordinator,
            Runnable refreshObservation
        ) {
            this.plan = Objects.requireNonNull(plan, "plan must not be null");
            this.refreshObservation = Objects.requireNonNull(
                refreshObservation,
                "refreshObservation must not be null"
            );
            handle = new ExecutionHandle(coordinator, this);
            states = plan.requirements().stream().map(RequirementState::new).toList();
        }
    }

    private static final class RequirementState {
        private final ProofPlan.Requirement requirement;
        private ProofResolution resolution = ProofResolution.NOT_EVALUATED;
        private ProofResolutionReason reason =
            ProofResolutionReason.NOT_EVALUATED_AFTER_TERMINAL_OUTCOME;
        private Optional<ConnectionId> connectionId = Optional.empty();
        private List<InteractionRef> interactions = List.of();

        private RequirementState(ProofPlan.Requirement requirement) {
            this.requirement = Objects.requireNonNull(
                requirement,
                "requirement must not be null"
            );
        }

        private void set(
            ProofResolution resolution,
            ProofResolutionReason reason,
            Optional<ConnectionId> connectionId,
            List<InteractionRef> interactions
        ) {
            this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
            this.connectionId = Objects.requireNonNull(
                connectionId,
                "connectionId must not be null"
            );
            this.interactions = List.copyOf(
                Objects.requireNonNull(interactions, "interactions must not be null")
            );
        }

        private ProofObligationResolution snapshot() {
            return new ProofObligationResolution(
                requirement.id(),
                requirement.kind(),
                resolution,
                reason,
                connectionId,
                interactions
            );
        }
    }

    private static final class ExecutionHandle implements ProofExecution {
        private final ProofExecutionCoordinator coordinator;
        private final ExecutionRecord record;

        private ExecutionHandle(
            ProofExecutionCoordinator coordinator,
            ExecutionRecord record
        ) {
            this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
            );
            this.record = Objects.requireNonNull(record, "record must not be null");
        }

        @Override
        public ProofExecutionState state() {
            synchronized (coordinator) {
                return record.state;
            }
        }

        @Override
        public void runStimulus(Runnable stimulus) {
            coordinator.runStimulus(record, stimulus);
        }

        @Override
        public ProofResult evaluate() {
            return coordinator.evaluate(record);
        }

        @Override
        public ProofResult result() {
            return coordinator.result(record);
        }
    }

    private static final class RuntimeProofPrerequisite implements ProofPrerequisite {
        private final Object owner;
        private final ProofPrerequisiteStatus status;
        private final Optional<FailureDetails> failure;

        private RuntimeProofPrerequisite(
            Object owner,
            ProofPrerequisiteStatus status,
            Optional<FailureDetails> failure
        ) {
            this.owner = Objects.requireNonNull(owner, "owner must not be null");
            this.status = Objects.requireNonNull(status, "status must not be null");
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        @Override
        public ProofPrerequisiteStatus status() {
            return status;
        }

        @Override
        public Optional<FailureDetails> failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "ProofPrerequisite[status=" + status + "]";
        }
    }

    private static final class ObservationFailure extends RuntimeException {
        private ObservationFailure() {}
    }

    private static final class ControlFailure extends RuntimeException {
        private ControlFailure() {}
    }

    private static final class EvaluationFailure extends RuntimeException {
        private EvaluationFailure() {}
    }

    private static final class UnfinishedProofExecution extends RuntimeException {
        private UnfinishedProofExecution() {}
    }
}
