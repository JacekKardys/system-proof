package io.github.jacekkardys.systemproof.environment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import io.github.jacekkardys.systemproof.control.SemanticControls;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticHoldFailure;
import io.github.jacekkardys.systemproof.control.SemanticHoldRef;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.control.SemanticInteractionSelector;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorBoundary;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuard;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardFailure;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardRef;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardSpec;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardState;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorViolation;
import io.github.jacekkardys.systemproof.environment.ProofSubjectRegistry.NativeFlowResolution;
import io.github.jacekkardys.systemproof.journal.SemanticPredecessorGuardEvent;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.ForwardingDecision;
import io.github.jacekkardys.systemproof.observation.ForwardingPermit;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.RecordedInteraction;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Environment-owned semantic-control registry, matcher, and linearization point. */
final class SemanticControlCoordinator
    implements SemanticControls, InteractionDecisionCoordinator {

    private static final long FIRST_CONTROL_VALUE = 1L;
    private static final ForwardingPermit IMMEDIATE_FORWARD =
        new TerminalPermit(ForwardingDecision.FORWARD);
    private static final ForwardingPermit CLOSE_SESSION =
        new TerminalPermit(ForwardingDecision.CLOSE_SESSION);

    private final Object holdOwner = new Object();
    private final Object guardOwner = new Object();
    private final EnvironmentEventPublisher events;
    private final ProofSubjectRegistry proofSubjects;
    private final SemanticControlCapabilityRegistry controlCapabilities;
    private final TimeoutScheduler timeoutScheduler;
    private final Map<RuntimeSemanticHoldRef, HoldEntry> activeHolds =
        new LinkedHashMap<>();
    private final Map<RuntimeSemanticPredecessorGuardRef, GuardEntry> guards =
        new LinkedHashMap<>();
    private final Set<ConnectionId> failedRequiredObservationConnections =
        new LinkedHashSet<>();
    private long nextHoldValue = FIRST_CONTROL_VALUE;
    private long nextGuardValue = FIRST_CONTROL_VALUE;
    private boolean acceptingNewControls = true;

    SemanticControlCoordinator(
        EnvironmentEventPublisher events,
        ProofSubjectRegistry proofSubjects,
        SemanticControlCapabilityRegistry controlCapabilities
    ) {
        this(events, proofSubjects, controlCapabilities, new SystemTimeoutScheduler());
    }

    SemanticControlCoordinator(
        EnvironmentEventPublisher events,
        ProofSubjectRegistry proofSubjects,
        SemanticControlCapabilityRegistry controlCapabilities,
        TimeoutScheduler timeoutScheduler
    ) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.proofSubjects = Objects.requireNonNull(
            proofSubjects,
            "proofSubjects must not be null"
        );
        this.controlCapabilities = Objects.requireNonNull(
            controlCapabilities,
            "controlCapabilities must not be null"
        );
        this.timeoutScheduler = Objects.requireNonNull(
            timeoutScheduler,
            "timeoutScheduler must not be null"
        );
    }

    @Override
    public <T> SemanticHold arm(
        SemanticInteractionSelector<T> selector,
        Duration maximumHoldDuration
    ) {
        selector = Objects.requireNonNull(selector, "selector must not be null");
        maximumHoldDuration = requirePositive(
            maximumHoldDuration,
            "maximumHoldDuration"
        );
        synchronized (this) {
            requireAccepting();
            validateSelector(selector);
            requireRequiredObservationAvailable(selector.connectionId());
            RuntimeSemanticHoldRef ref = nextHoldReference();
            HoldEntry entry = new HoldEntry(ref, selector, maximumHoldDuration);
            activeHolds.put(ref, entry);
            appendHold(entry, SemanticHoldState.ARMED, Optional.empty());
            return new SemanticHoldHandle(this, entry);
        }
    }

    @Override
    public SemanticPredecessorGuard guard(
        SemanticPredecessorGuardSpec specification
    ) {
        specification = Objects.requireNonNull(
            specification,
            "specification must not be null"
        );
        List<Runnable> afterTransition = new ArrayList<>();
        GuardEntry entry;
        synchronized (this) {
            requireAccepting();
            proofSubjects.validateSubject(specification.subject());
            validateSelector(specification.predecessor().selector());
            validateSelector(specification.successor());
            requireRequiredObservationAvailable(
                specification.predecessor().selector().connectionId()
            );
            requireRequiredObservationAvailable(
                specification.successor().connectionId()
            );
            RuntimeSemanticPredecessorGuardRef ref = nextGuardReference();
            entry = new GuardEntry(ref, specification);
            guards.put(ref, entry);
            appendGuardState(entry, SemanticPredecessorGuardState.ARMED, Optional.empty());
            try {
                TimeoutTask scheduled = timeoutScheduler.schedule(
                    specification.maximumDuration(),
                    () -> timeout(entry)
                );
                if (guardAwaitsTimedBoundary(entry.state)) {
                    entry.timeoutTask = scheduled;
                } else {
                    scheduled.cancel();
                }
            } catch (RuntimeException | Error schedulingFailure) {
                failGuardLocked(
                    entry,
                    SemanticPredecessorGuardFailure.INTERNAL_FAILURE,
                    afterTransition
                );
            }
        }
        runAfterTransition(afterTransition);
        return new SemanticPredecessorGuardHandle(this, entry);
    }

    @Override
    public ForwardingPermit permit(RecordedInteraction interaction) {
        interaction = Objects.requireNonNull(interaction, "interaction must not be null");
        List<Runnable> afterTransition = new ArrayList<>();
        ForwardingPermit permit;
        synchronized (this) {
            permit = decideLocked(interaction, afterTransition);
        }
        runAfterTransition(afterTransition);
        return permit;
    }

    @Override
    public void observationFailed(ConnectionId connectionId) {
        connectionId = Objects.requireNonNull(connectionId, "connectionId must not be null");
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            failedRequiredObservationConnections.add(connectionId);
            for (GuardEntry entry : List.copyOf(guards.values())) {
                if (guardIsActiveForFailureOrTeardown(entry.state)
                    && entry.concerns(connectionId)) {
                    failGuardLocked(
                        entry,
                        SemanticPredecessorGuardFailure.REQUIRED_OBSERVATION_FAILURE,
                        afterTransition
                    );
                } else if (entry.state == SemanticPredecessorGuardState.VIOLATED
                    && entry.concerns(connectionId)) {
                    appendGuardSuppressedFailure(
                        entry,
                        SemanticPredecessorGuardFailure.REQUIRED_OBSERVATION_FAILURE
                    );
                }
            }
        }
        runAfterTransition(afterTransition);
    }

    private ForwardingPermit decideLocked(
        RecordedInteraction interaction,
        List<Runnable> afterTransition
    ) {
        List<GuardUse> forwardedPredecessors = observePredecessorsLocked(
            interaction,
            afterTransition
        );
        GuardDecision guardDecision = decideGuardSuccessorsLocked(
            interaction,
            afterTransition
        );
        if (guardDecision.closeSession) {
            abortGuardUsesLocked(
                forwardedPredecessors,
                SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                afterTransition
            );
            abortGuardUsesLocked(
                guardDecision.authorizedSuccessors,
                SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                afterTransition
            );
            return CLOSE_SESSION;
        }

        HoldMatch holdMatch = selectHoldLocked(interaction, afterTransition);
        if (holdMatch.failedClosed) {
            abortGuardUsesLocked(
                forwardedPredecessors,
                SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                afterTransition
            );
            abortGuardUsesLocked(
                guardDecision.authorizedSuccessors,
                SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                afterTransition
            );
            return CLOSE_SESSION;
        }

        HoldEntry held = holdMatch.entry;
        if (held != null) {
            reachHoldLocked(held, holdMatch.selection, interaction, afterTransition);
            if (held.state != SemanticHoldState.REACHED_HELD) {
                abortGuardUsesLocked(
                    forwardedPredecessors,
                    SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                    afterTransition
                );
                abortGuardUsesLocked(
                    guardDecision.authorizedSuccessors,
                    SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                    afterTransition
                );
                return CLOSE_SESSION;
            }
        }

        if (held == null
            && forwardedPredecessors.isEmpty()
            && guardDecision.authorizedSuccessors.isEmpty()) {
            return IMMEDIATE_FORWARD;
        }
        PermitContext context = new PermitContext(
            held,
            List.copyOf(forwardedPredecessors),
            List.copyOf(guardDecision.authorizedSuccessors)
        );
        CoordinatedPermit permit = new CoordinatedPermit(this, context);
        context.permit = permit;
        if (held != null) {
            held.permit = permit;
        } else {
            permit.authorize(ForwardingDecision.FORWARD);
        }
        return permit;
    }

    private List<GuardUse> observePredecessorsLocked(
        RecordedInteraction interaction,
        List<Runnable> afterTransition
    ) {
        List<GuardUse> forwardedPredecessors = new ArrayList<>();
        for (GuardEntry entry : guards.values()) {
            if (entry.state != SemanticPredecessorGuardState.ARMED) {
                continue;
            }
            SelectorSelection selection;
            try {
                selection = select(entry.predecessorSelector, interaction);
            } catch (RuntimeException | Error failure) {
                failGuardLocked(
                    entry,
                    SemanticPredecessorGuardFailure.SELECTOR_EVALUATION,
                    afterTransition
                );
                continue;
            }
            if (selection == null) {
                continue;
            }
            entry.predecessor = interaction.interactionRef();
            entry.predecessorSelection = selection;
            if (entry.requiredBoundary == SemanticPredecessorBoundary.CONFIRMED) {
                transitionGuardLocked(
                    entry,
                    SemanticPredecessorGuardState.PREDECESSOR_SATISFIED,
                    Optional.empty()
                );
            } else {
                transitionGuardLocked(
                    entry,
                    SemanticPredecessorGuardState.PREDECESSOR_OBSERVED,
                    Optional.empty()
                );
                forwardedPredecessors.add(new GuardUse(entry, selection));
            }
        }
        return forwardedPredecessors;
    }

    private GuardDecision decideGuardSuccessorsLocked(
        RecordedInteraction interaction,
        List<Runnable> afterTransition
    ) {
        List<GuardUse> authorized = new ArrayList<>();
        boolean close = false;
        for (GuardEntry entry : guards.values()) {
            if (!guardEnforcesLaterTarget(entry)) {
                continue;
            }
            SelectorSelection selection;
            try {
                selection = select(entry.successorSelector, interaction);
            } catch (RuntimeException | Error failure) {
                if (guardAwaitsTimedBoundary(entry.state)) {
                    failGuardLocked(
                        entry,
                        SemanticPredecessorGuardFailure.SELECTOR_EVALUATION,
                        afterTransition
                    );
                }
                close = true;
                continue;
            }
            if (selection == null) {
                continue;
            }
            entry.successor = interaction.interactionRef();
            entry.successorSelection = selection;
            switch (entry.state) {
                case PREDECESSOR_SATISFIED -> {
                    transitionGuardLocked(
                        entry,
                        SemanticPredecessorGuardState.SUCCESSOR_AUTHORIZED,
                        Optional.empty()
                    );
                    cancelTimeout(entry);
                    appendGuardDecision(entry, ForwardingDecision.FORWARD);
                    authorized.add(new GuardUse(entry, selection));
                }
                case ARMED, PREDECESSOR_OBSERVED -> {
                    terminalGuardLocked(
                        entry,
                        SemanticPredecessorGuardState.VIOLATED,
                        Optional.empty(),
                        afterTransition
                    );
                    appendGuardViolation(entry);
                    close = true;
                }
                case VIOLATED, CANCELLED, TIMED_OUT, FAILED -> {
                    appendGuardDecision(entry, ForwardingDecision.CLOSE_SESSION);
                    close = true;
                }
                default -> throw new IllegalStateException(
                    "Unexpected enforcing guard state " + entry.state
                );
            }
        }
        return new GuardDecision(close, authorized);
    }

    private HoldMatch selectHoldLocked(
        RecordedInteraction interaction,
        List<Runnable> afterTransition
    ) {
        List<HoldSelection> matches = new ArrayList<>();
        for (HoldEntry entry : activeHolds.values()) {
            if (entry.state != SemanticHoldState.ARMED) {
                continue;
            }
            SelectorSelection selection;
            try {
                selection = select(entry.selector, interaction);
            } catch (RuntimeException | Error failure) {
                entry.interactionRef = interaction.interactionRef();
                failHoldLocked(
                    entry,
                    SemanticHoldFailure.SELECTOR_EVALUATION,
                    afterTransition
                );
                return HoldMatch.failed();
            }
            if (selection != null) {
                matches.add(new HoldSelection(entry, selection));
            }
        }
        if (matches.size() > 1) {
            for (HoldSelection match : matches) {
                match.entry.interactionRef = interaction.interactionRef();
                failHoldLocked(
                    match.entry,
                    SemanticHoldFailure.AMBIGUOUS_MATCH,
                    afterTransition
                );
            }
            return HoldMatch.failed();
        }
        if (matches.isEmpty()) {
            return HoldMatch.none();
        }
        HoldSelection selected = matches.getFirst();
        return new HoldMatch(selected.entry, selected.selection, false);
    }

    private void reachHoldLocked(
        HoldEntry entry,
        SelectorSelection selection,
        RecordedInteraction interaction,
        List<Runnable> afterTransition
    ) {
        entry.interactionRef = interaction.interactionRef();
        entry.selection = selection;
        transitionHoldLocked(entry, SemanticHoldState.REACHED_HELD, Optional.empty());
        entry.reachedEstablished = true;
        afterTransition.add(() -> entry.reached.complete(entry.interactionRef));
        try {
            TimeoutTask scheduled = timeoutScheduler.schedule(
                entry.maximumHoldDuration,
                () -> timeout(entry)
            );
            if (entry.state == SemanticHoldState.REACHED_HELD) {
                entry.timeoutTask = scheduled;
            } else {
                scheduled.cancel();
            }
        } catch (RuntimeException | Error schedulingFailure) {
            failHoldLocked(entry, SemanticHoldFailure.INTERNAL_FAILURE, afterTransition);
        }
    }

    private SelectorSelection select(
        SemanticInteractionSelector<?> selector,
        RecordedInteraction interaction
    ) {
        InteractionRef reference = interaction.interactionRef();
        if (!selector.connectionId().equals(reference.connectionId())
            || selector.direction() != reference.direction()
            || !selector.evidenceSchema().equals(interaction.evidence().schemaId())
            || !matches(selector, interaction.evidence())) {
            return null;
        }
        if (selector.proofSubject().isEmpty()) {
            return new SelectorSelection(selector, reference, null);
        }
        ProofSubjectRef subject = selector.proofSubject().orElseThrow();
        Optional<CorrelationKey> nativeFlowKey = selector.nativeFlowCorrelationKey();
        if (nativeFlowKey.isEmpty()) {
            return proofSubjects.isSoleUniqueSubjectFor(subject, reference)
                ? new SelectorSelection(selector, reference, null)
                : null;
        }
        Optional<NativeFlowResolution> resolved = proofSubjects.soleUniqueNativeFlow(
            subject,
            nativeFlowKey.orElseThrow(),
            selector.nativeFlowReferenceSchema().orElseThrow()
        );
        if (resolved.isEmpty()) {
            return null;
        }
        NativeFlowResolution nativeFlow = resolved.orElseThrow();
        if (!nativeFlow.containsCandidate(reference)
            || !matchesNativeFlow(
                selector,
                interaction.evidence(),
                nativeFlow.nativeReference()
            )
            || !proofSubjects.remainsSoleUniqueNativeFlow(nativeFlow)) {
            return null;
        }
        return new SelectorSelection(selector, reference, nativeFlow);
    }

    private static <T> boolean matches(
        SemanticInteractionSelector<T> selector,
        EvidenceSnapshot evidence
    ) {
        return selector.matches(evidence.decode(selector.evidenceCodec()));
    }

    private static <T> boolean matchesNativeFlow(
        SemanticInteractionSelector<T> selector,
        EvidenceSnapshot evidence,
        EvidenceSnapshot nativeReference
    ) {
        Object resolved = nativeReference.decode(
            selector.nativeFlowReferenceCodec().orElseThrow()
        );
        return selector.matchesNativeFlow(
            evidence.decode(selector.evidenceCodec()),
            resolved
        );
    }

    private void validateSelector(SemanticInteractionSelector<?> selector) {
        controlCapabilities.validateSelector(selector);
        selector.proofSubject().ifPresent(proofSubjects::validateSubject);
        selector.nativeFlowCorrelationKey().ifPresent(key ->
            proofSubjects.validateSubjectFlow(
                selector.proofSubject().orElseThrow(),
                key
            )
        );
    }

    private SemanticHoldState state(HoldEntry entry) {
        synchronized (this) {
            return entry.state;
        }
    }

    private SemanticPredecessorGuardState state(GuardEntry entry) {
        synchronized (this) {
            return entry.state;
        }
    }

    private CompletionStage<Void> release(HoldEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        CompletionStage<Void> result;
        synchronized (this) {
            if (entry.state != SemanticHoldState.REACHED_HELD) {
                return failedStage(
                    "Semantic hold cannot be released from state " + entry.state
                );
            }
            result = entry.releaseCompletion.minimalCompletionStage();
            if (!entry.selection.remainsValid(proofSubjects)) {
                failHoldLocked(
                    entry,
                    SemanticHoldFailure.CORRELATION_INVALIDATED,
                    afterTransition
                );
            } else {
                transitionHoldLocked(entry, SemanticHoldState.RELEASING, Optional.empty());
                cancelTimeout(entry);
                afterTransition.add(
                    () -> entry.permit.authorize(ForwardingDecision.FORWARD)
                );
            }
        }
        runAfterTransition(afterTransition);
        return result;
    }

    private boolean cancel(HoldEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (entry.state != SemanticHoldState.ARMED
                && entry.state != SemanticHoldState.REACHED_HELD) {
                return false;
            }
            terminalHoldLocked(
                entry,
                SemanticHoldState.CANCELLED,
                Optional.empty(),
                afterTransition
            );
        }
        runAfterTransition(afterTransition);
        return true;
    }

    private boolean cancel(GuardEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (!guardAwaitsTimedBoundary(entry.state)) {
                return false;
            }
            terminalGuardLocked(
                entry,
                SemanticPredecessorGuardState.CANCELLED,
                Optional.empty(),
                afterTransition
            );
        }
        runAfterTransition(afterTransition);
        return true;
    }

    private void timeout(HoldEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (entry.state != SemanticHoldState.REACHED_HELD) {
                return;
            }
            terminalHoldLocked(
                entry,
                SemanticHoldState.TIMED_OUT,
                Optional.empty(),
                afterTransition
            );
        }
        runAfterTransition(afterTransition);
    }

    private void timeout(GuardEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (!guardAwaitsTimedBoundary(entry.state)) {
                return;
            }
            terminalGuardLocked(
                entry,
                SemanticPredecessorGuardState.TIMED_OUT,
                Optional.empty(),
                afterTransition
            );
        }
        runAfterTransition(afterTransition);
    }

    private void forwarded(PermitContext context) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (!context.claimOutcome()) {
                return;
            }
            for (GuardUse use : context.authorizedSuccessors) {
                GuardEntry entry = use.entry;
                if (entry.state != SemanticPredecessorGuardState.SUCCESSOR_AUTHORIZED) {
                    continue;
                }
                if (!use.selection.remainsValid(proofSubjects)) {
                    failGuardLocked(
                        entry,
                        SemanticPredecessorGuardFailure.CORRELATION_INVALIDATED,
                        afterTransition
                    );
                    continue;
                }
                terminalGuardLocked(
                    entry,
                    SemanticPredecessorGuardState.SATISFIED,
                    Optional.empty(),
                    afterTransition
                );
                appendGuardRelation(entry);
            }
            for (GuardUse use : context.forwardedPredecessors) {
                GuardEntry entry = use.entry;
                if (entry.state != SemanticPredecessorGuardState.PREDECESSOR_OBSERVED) {
                    continue;
                }
                if (!use.selection.remainsValid(proofSubjects)) {
                    failGuardLocked(
                        entry,
                        SemanticPredecessorGuardFailure.CORRELATION_INVALIDATED,
                        afterTransition
                    );
                    continue;
                }
                transitionGuardLocked(
                    entry,
                    SemanticPredecessorGuardState.PREDECESSOR_SATISFIED,
                    Optional.empty()
                );
            }
            if (context.hold != null
                && context.hold.state == SemanticHoldState.RELEASING) {
                terminalHoldLocked(
                    context.hold,
                    SemanticHoldState.FORWARDED,
                    Optional.empty(),
                    afterTransition
                );
            }
        }
        runAfterTransition(afterTransition);
    }

    private void writeFailed(PermitContext context) {
        failPermit(context, SemanticPredecessorGuardFailure.WRITE_FAILURE,
            SemanticHoldFailure.WRITE_FAILURE);
    }

    private void abandoned(PermitContext context) {
        failPermit(context, SemanticPredecessorGuardFailure.SESSION_ABANDONED,
            SemanticHoldFailure.SESSION_ABANDONED);
    }

    private void failPermit(
        PermitContext context,
        SemanticPredecessorGuardFailure guardFailure,
        SemanticHoldFailure holdFailure
    ) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (!context.claimOutcome()) {
                return;
            }
            abortGuardUsesLocked(
                context.authorizedSuccessors,
                guardFailure,
                afterTransition
            );
            abortGuardUsesLocked(
                context.forwardedPredecessors,
                guardFailure,
                afterTransition
            );
            if (context.hold != null
                && (context.hold.state == SemanticHoldState.REACHED_HELD
                    || context.hold.state == SemanticHoldState.RELEASING)) {
                failHoldLocked(context.hold, holdFailure, afterTransition);
            }
        }
        runAfterTransition(afterTransition);
    }

    void completeExecution() {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (!acceptingNewControls) {
                return;
            }
            acceptingNewControls = false;
            for (HoldEntry entry : List.copyOf(activeHolds.values())) {
                if (entry.state == SemanticHoldState.ARMED
                    || entry.state == SemanticHoldState.REACHED_HELD) {
                    terminalHoldLocked(
                        entry,
                        SemanticHoldState.CANCELLED,
                        Optional.empty(),
                        afterTransition
                    );
                }
            }
            for (GuardEntry entry : List.copyOf(guards.values())) {
                if (guardIsActiveForFailureOrTeardown(entry.state)) {
                    entry.retainCancelledEnforcement = true;
                    terminalGuardLocked(
                        entry,
                        SemanticPredecessorGuardState.CANCELLED,
                        Optional.empty(),
                        afterTransition
                    );
                }
            }
        }
        runAfterTransition(afterTransition);
        timeoutScheduler.close();
    }

    private void abortGuardUsesLocked(
        List<GuardUse> uses,
        SemanticPredecessorGuardFailure failure,
        List<Runnable> afterTransition
    ) {
        for (GuardUse use : uses) {
            GuardEntry entry = use.entry;
            if (entry.state == SemanticPredecessorGuardState.PREDECESSOR_OBSERVED
                || entry.state == SemanticPredecessorGuardState.SUCCESSOR_AUTHORIZED) {
                failGuardLocked(entry, failure, afterTransition);
            }
        }
    }

    private void failHoldLocked(
        HoldEntry entry,
        SemanticHoldFailure failure,
        List<Runnable> afterTransition
    ) {
        terminalHoldLocked(
            entry,
            SemanticHoldState.FAILED,
            Optional.of(Objects.requireNonNull(failure, "failure must not be null")),
            afterTransition
        );
    }

    private void terminalHoldLocked(
        HoldEntry entry,
        SemanticHoldState terminalState,
        Optional<SemanticHoldFailure> failure,
        List<Runnable> afterTransition
    ) {
        transitionHoldLocked(entry, terminalState, failure);
        cancelTimeout(entry);
        activeHolds.remove(entry.ref);
        entry.selector = null;
        entry.selection = null;
        if (entry.permit != null && terminalState != SemanticHoldState.FORWARDED) {
            abortGuardUsesLocked(
                entry.permit.context.forwardedPredecessors,
                SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                afterTransition
            );
            abortGuardUsesLocked(
                entry.permit.context.authorizedSuccessors,
                SemanticPredecessorGuardFailure.SESSION_ABANDONED,
                afterTransition
            );
            afterTransition.add(
                () -> entry.permit.authorize(ForwardingDecision.CLOSE_SESSION)
            );
        }
        IllegalStateException terminalFailure = terminalFailure("hold", entry.state);
        if (!entry.reachedEstablished && !entry.reached.isDone()) {
            afterTransition.add(() -> entry.reached.completeExceptionally(terminalFailure));
        }
        if (terminalState == SemanticHoldState.FORWARDED) {
            afterTransition.add(() -> entry.releaseCompletion.complete(null));
        } else if (!entry.releaseCompletion.isDone()) {
            afterTransition.add(
                () -> entry.releaseCompletion.completeExceptionally(terminalFailure)
            );
        }
        afterTransition.add(() -> entry.completion.complete(terminalState));
    }

    private void transitionHoldLocked(
        HoldEntry entry,
        SemanticHoldState next,
        Optional<SemanticHoldFailure> failure
    ) {
        entry.state = Objects.requireNonNull(next, "next must not be null");
        appendHold(entry, next, failure);
    }

    private void failGuardLocked(
        GuardEntry entry,
        SemanticPredecessorGuardFailure failure,
        List<Runnable> afterTransition
    ) {
        if (!guardIsActiveForFailureOrTeardown(entry.state)) {
            return;
        }
        terminalGuardLocked(
            entry,
            SemanticPredecessorGuardState.FAILED,
            Optional.of(Objects.requireNonNull(failure, "failure must not be null")),
            afterTransition
        );
    }

    private void terminalGuardLocked(
        GuardEntry entry,
        SemanticPredecessorGuardState terminalState,
        Optional<SemanticPredecessorGuardFailure> failure,
        List<Runnable> afterTransition
    ) {
        transitionGuardLocked(entry, terminalState, failure);
        cancelTimeout(entry);
        if (terminalState == SemanticPredecessorGuardState.SATISFIED
            || (terminalState == SemanticPredecessorGuardState.CANCELLED
                && !entry.retainCancelledEnforcement)) {
            guards.remove(entry.ref);
        }
        if (!entry.completion.isDone()) {
            afterTransition.add(() -> entry.completion.complete(terminalState));
        }
    }

    private void transitionGuardLocked(
        GuardEntry entry,
        SemanticPredecessorGuardState next,
        Optional<SemanticPredecessorGuardFailure> failure
    ) {
        entry.state = Objects.requireNonNull(next, "next must not be null");
        appendGuardState(entry, next, failure);
    }

    private void appendHold(
        HoldEntry entry,
        SemanticHoldState state,
        Optional<SemanticHoldFailure> failure
    ) {
        events.semanticHold(
            entry.ref,
            state,
            entry.connectionId,
            entry.direction,
            entry.evidenceSchema,
            entry.proofSubject,
            Optional.ofNullable(entry.interactionRef),
            failure
        );
    }

    private void appendGuardState(
        GuardEntry entry,
        SemanticPredecessorGuardState state,
        Optional<SemanticPredecessorGuardFailure> failure
    ) {
        appendGuardFact(
            entry,
            SemanticPredecessorGuardEvent.Kind.STATE,
            Optional.empty(),
            Optional.empty(),
            failure
        );
    }

    private void appendGuardDecision(
        GuardEntry entry,
        ForwardingDecision decision
    ) {
        appendGuardFact(
            entry,
            SemanticPredecessorGuardEvent.Kind.DECISION,
            Optional.of(decision),
            Optional.empty(),
            Optional.empty()
        );
    }

    private void appendGuardRelation(GuardEntry entry) {
        appendGuardFact(
            entry,
            SemanticPredecessorGuardEvent.Kind.RELATION,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private void appendGuardViolation(GuardEntry entry) {
        appendGuardFact(
            entry,
            SemanticPredecessorGuardEvent.Kind.VIOLATION,
            Optional.of(ForwardingDecision.CLOSE_SESSION),
            Optional.of(SemanticPredecessorViolation.PREDECESSOR_NOT_ESTABLISHED),
            Optional.empty()
        );
    }

    private void appendGuardSuppressedFailure(
        GuardEntry entry,
        SemanticPredecessorGuardFailure failure
    ) {
        appendGuardFact(
            entry,
            SemanticPredecessorGuardEvent.Kind.SUPPRESSED_FAILURE,
            Optional.empty(),
            Optional.empty(),
            Optional.of(Objects.requireNonNull(failure, "failure must not be null"))
        );
    }

    private void appendGuardFact(
        GuardEntry entry,
        SemanticPredecessorGuardEvent.Kind kind,
        Optional<ForwardingDecision> decision,
        Optional<SemanticPredecessorViolation> violation,
        Optional<SemanticPredecessorGuardFailure> failure
    ) {
        events.semanticPredecessorGuard(
            entry.ref,
            kind,
            entry.subject,
            entry.state,
            entry.requiredBoundary,
            Optional.ofNullable(entry.predecessor),
            Optional.ofNullable(entry.successor),
            decision,
            violation,
            failure
        );
    }

    private RuntimeSemanticHoldRef nextHoldReference() {
        if (nextHoldValue < FIRST_CONTROL_VALUE) {
            throw exhausted("Semantic-hold");
        }
        RuntimeSemanticHoldRef ref = new RuntimeSemanticHoldRef(holdOwner, nextHoldValue);
        nextHoldValue = increment(nextHoldValue);
        return ref;
    }

    private RuntimeSemanticPredecessorGuardRef nextGuardReference() {
        if (nextGuardValue < FIRST_CONTROL_VALUE) {
            throw exhausted("Semantic-predecessor-guard");
        }
        RuntimeSemanticPredecessorGuardRef ref =
            new RuntimeSemanticPredecessorGuardRef(guardOwner, nextGuardValue);
        nextGuardValue = increment(nextGuardValue);
        return ref;
    }

    private void requireAccepting() {
        if (!acceptingNewControls) {
            throw new IllegalStateException(
                "Environment execution is complete and cannot arm semantic controls"
            );
        }
    }

    private void requireRequiredObservationAvailable(ConnectionId connectionId) {
        if (failedRequiredObservationConnections.contains(connectionId)) {
            throw new IllegalStateException(
                "Connection '" + connectionId
                    + "' has terminal required-observation failure"
            );
        }
    }

    private static boolean guardAwaitsTimedBoundary(
        SemanticPredecessorGuardState state
    ) {
        return state == SemanticPredecessorGuardState.ARMED
            || state == SemanticPredecessorGuardState.PREDECESSOR_OBSERVED
            || state == SemanticPredecessorGuardState.PREDECESSOR_SATISFIED;
    }

    private static boolean guardIsActiveForFailureOrTeardown(
        SemanticPredecessorGuardState state
    ) {
        return guardAwaitsTimedBoundary(state)
            || state == SemanticPredecessorGuardState.SUCCESSOR_AUTHORIZED;
    }

    private static boolean guardEnforcesLaterTarget(GuardEntry entry) {
        return guardAwaitsTimedBoundary(entry.state)
            || entry.state == SemanticPredecessorGuardState.VIOLATED
            || entry.state == SemanticPredecessorGuardState.TIMED_OUT
            || entry.state == SemanticPredecessorGuardState.FAILED
            || (entry.state == SemanticPredecessorGuardState.CANCELLED
                && entry.retainCancelledEnforcement);
    }

    private static void cancelTimeout(HoldEntry entry) {
        if (entry.timeoutTask != null) {
            entry.timeoutTask.cancel();
            entry.timeoutTask = null;
        }
    }

    private static void cancelTimeout(GuardEntry entry) {
        if (entry.timeoutTask != null) {
            entry.timeoutTask.cancel();
            entry.timeoutTask = null;
        }
    }

    private static IllegalStateException terminalFailure(String kind, Object state) {
        return new IllegalStateException("Semantic " + kind + " completed with state " + state);
    }

    private static CompletionStage<Void> failedStage(String message) {
        return CompletableFuture.<Void>failedFuture(new IllegalStateException(message))
            .minimalCompletionStage();
    }

    private static Duration requirePositive(Duration value, String description) {
        value = Objects.requireNonNull(value, description + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(description + " must be positive");
        }
        return value;
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MIN_VALUE : value + 1L;
    }

    private static IllegalStateException exhausted(String kind) {
        return new IllegalStateException(
            kind + " identity space is exhausted for this environment execution"
        );
    }

    private static void runAfterTransition(List<Runnable> actions) {
        for (Runnable action : actions) {
            action.run();
        }
    }

    interface TimeoutScheduler extends AutoCloseable {
        TimeoutTask schedule(Duration delay, Runnable action);

        @Override
        void close();
    }

    @FunctionalInterface
    interface TimeoutTask {
        void cancel();
    }

    private static final class SystemTimeoutScheduler implements TimeoutScheduler {
        private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform()
                    .daemon(true)
                    .name("system-proof-semantic-control-timeouts")
                    .unstarted(runnable)
            );

        @Override
        public TimeoutTask schedule(Duration delay, Runnable action) {
            ScheduledFuture<?> future = executor.schedule(
                Objects.requireNonNull(action, "action must not be null"),
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            );
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class HoldEntry {
        private final RuntimeSemanticHoldRef ref;
        private final Duration maximumHoldDuration;
        private final ConnectionId connectionId;
        private final io.github.jacekkardys.systemproof.observation.FlowDirection direction;
        private final io.github.jacekkardys.systemproof.observation.EvidenceSchemaId evidenceSchema;
        private final Optional<ProofSubjectRef> proofSubject;
        private final CompletableFuture<InteractionRef> reached = new CompletableFuture<>();
        private final CompletableFuture<SemanticHoldState> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> releaseCompletion = new CompletableFuture<>();
        private SemanticInteractionSelector<?> selector;
        private SemanticHoldState state = SemanticHoldState.ARMED;
        private InteractionRef interactionRef;
        private SelectorSelection selection;
        private boolean reachedEstablished;
        private CoordinatedPermit permit;
        private TimeoutTask timeoutTask;

        private HoldEntry(
            RuntimeSemanticHoldRef ref,
            SemanticInteractionSelector<?> selector,
            Duration maximumHoldDuration
        ) {
            this.ref = Objects.requireNonNull(ref, "ref must not be null");
            this.selector = Objects.requireNonNull(selector, "selector must not be null");
            this.maximumHoldDuration = Objects.requireNonNull(
                maximumHoldDuration,
                "maximumHoldDuration must not be null"
            );
            connectionId = selector.connectionId();
            direction = selector.direction();
            evidenceSchema = selector.evidenceSchema();
            proofSubject = selector.proofSubject();
        }
    }

    private static final class GuardEntry {
        private final RuntimeSemanticPredecessorGuardRef ref;
        private final ProofSubjectRef subject;
        private final SemanticPredecessorBoundary requiredBoundary;
        private final SemanticInteractionSelector<?> predecessorSelector;
        private final SemanticInteractionSelector<?> successorSelector;
        private final CompletableFuture<SemanticPredecessorGuardState> completion =
            new CompletableFuture<>();
        private SemanticPredecessorGuardState state =
            SemanticPredecessorGuardState.ARMED;
        private InteractionRef predecessor;
        private InteractionRef successor;
        private SelectorSelection predecessorSelection;
        private SelectorSelection successorSelection;
        private TimeoutTask timeoutTask;
        private boolean retainCancelledEnforcement;

        private GuardEntry(
            RuntimeSemanticPredecessorGuardRef ref,
            SemanticPredecessorGuardSpec specification
        ) {
            this.ref = Objects.requireNonNull(ref, "ref must not be null");
            subject = specification.subject();
            requiredBoundary = specification.predecessor().boundary();
            predecessorSelector = specification.predecessor().selector();
            successorSelector = specification.successor();
        }

        private boolean concerns(ConnectionId connectionId) {
            return predecessorSelector.connectionId().equals(connectionId)
                || successorSelector.connectionId().equals(connectionId);
        }
    }

    private record SelectorSelection(
        SemanticInteractionSelector<?> selector,
        InteractionRef interaction,
        NativeFlowResolution nativeFlow
    ) {
        private SelectorSelection {
            Objects.requireNonNull(selector, "selector must not be null");
            Objects.requireNonNull(interaction, "interaction must not be null");
        }

        private boolean remainsValid(ProofSubjectRegistry proofSubjects) {
            if (nativeFlow != null) {
                return proofSubjects.remainsSoleUniqueNativeFlow(nativeFlow);
            }
            return selector.proofSubject()
                .map(subject -> proofSubjects.isSoleUniqueSubjectFor(subject, interaction))
                .orElse(true);
        }
    }

    private record HoldSelection(HoldEntry entry, SelectorSelection selection) {}

    private record HoldMatch(
        HoldEntry entry,
        SelectorSelection selection,
        boolean failedClosed
    ) {
        private static HoldMatch none() {
            return new HoldMatch(null, null, false);
        }

        private static HoldMatch failed() {
            return new HoldMatch(null, null, true);
        }
    }

    private record GuardUse(GuardEntry entry, SelectorSelection selection) {}

    private record GuardDecision(
        boolean closeSession,
        List<GuardUse> authorizedSuccessors
    ) {}

    private static final class PermitContext {
        private final HoldEntry hold;
        private final List<GuardUse> forwardedPredecessors;
        private final List<GuardUse> authorizedSuccessors;
        private CoordinatedPermit permit;
        private boolean outcomeClaimed;

        private PermitContext(
            HoldEntry hold,
            List<GuardUse> forwardedPredecessors,
            List<GuardUse> authorizedSuccessors
        ) {
            this.hold = hold;
            this.forwardedPredecessors = forwardedPredecessors;
            this.authorizedSuccessors = authorizedSuccessors;
        }

        private boolean claimOutcome() {
            if (outcomeClaimed) {
                return false;
            }
            outcomeClaimed = true;
            return true;
        }
    }

    private static final class SemanticHoldHandle implements SemanticHold {
        private final SemanticControlCoordinator coordinator;
        private final HoldEntry entry;

        private SemanticHoldHandle(
            SemanticControlCoordinator coordinator,
            HoldEntry entry
        ) {
            this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
            );
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
        }

        @Override
        public SemanticHoldRef ref() {
            return entry.ref;
        }

        @Override
        public SemanticHoldState state() {
            return coordinator.state(entry);
        }

        @Override
        public CompletionStage<InteractionRef> reached() {
            return entry.reached.minimalCompletionStage();
        }

        @Override
        public CompletionStage<SemanticHoldState> completion() {
            return entry.completion.minimalCompletionStage();
        }

        @Override
        public CompletionStage<Void> release() {
            return coordinator.release(entry);
        }

        @Override
        public boolean cancel() {
            return coordinator.cancel(entry);
        }
    }

    private static final class SemanticPredecessorGuardHandle
        implements SemanticPredecessorGuard {
        private final SemanticControlCoordinator coordinator;
        private final GuardEntry entry;

        private SemanticPredecessorGuardHandle(
            SemanticControlCoordinator coordinator,
            GuardEntry entry
        ) {
            this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
            );
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
        }

        @Override
        public SemanticPredecessorGuardRef ref() {
            return entry.ref;
        }

        @Override
        public SemanticPredecessorGuardState state() {
            return coordinator.state(entry);
        }

        @Override
        public CompletionStage<SemanticPredecessorGuardState> completion() {
            return entry.completion.minimalCompletionStage();
        }

        @Override
        public boolean cancel() {
            return coordinator.cancel(entry);
        }
    }

    private static final class CoordinatedPermit implements ForwardingPermit {
        private final SemanticControlCoordinator coordinator;
        private final PermitContext context;
        private final CompletableFuture<ForwardingDecision> decision =
            new CompletableFuture<>();

        private CoordinatedPermit(
            SemanticControlCoordinator coordinator,
            PermitContext context
        ) {
            this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
            );
            this.context = Objects.requireNonNull(context, "context must not be null");
        }

        private void authorize(ForwardingDecision authorization) {
            decision.complete(authorization);
        }

        @Override
        public ForwardingDecision awaitDecision() throws InterruptedException {
            try {
                return decision.get();
            } catch (ExecutionException impossible) {
                throw new CompletionException(impossible.getCause());
            }
        }

        @Override
        public void forwarded() {
            coordinator.forwarded(context);
        }

        @Override
        public void writeFailed() {
            coordinator.writeFailed(context);
        }

        @Override
        public void abandoned() {
            coordinator.abandoned(context);
        }
    }

    private record RuntimeSemanticHoldRef(Object owner, long value)
        implements SemanticHoldRef {
        private RuntimeSemanticHoldRef {
            Objects.requireNonNull(owner, "owner must not be null");
            if (value < FIRST_CONTROL_VALUE) {
                throw new IllegalArgumentException(
                    "semantic-hold value must be at least " + FIRST_CONTROL_VALUE
                );
            }
        }

        @Override
        public String toString() {
            return "semantic-hold-" + value;
        }
    }

    private record RuntimeSemanticPredecessorGuardRef(Object owner, long value)
        implements SemanticPredecessorGuardRef {
        private RuntimeSemanticPredecessorGuardRef {
            Objects.requireNonNull(owner, "owner must not be null");
            if (value < FIRST_CONTROL_VALUE) {
                throw new IllegalArgumentException(
                    "semantic-predecessor-guard value must be at least "
                        + FIRST_CONTROL_VALUE
                );
            }
        }

        @Override
        public String toString() {
            return "semantic-predecessor-guard-" + value;
        }
    }

    private record TerminalPermit(ForwardingDecision decision)
        implements ForwardingPermit {
        private TerminalPermit {
            Objects.requireNonNull(decision, "decision must not be null");
        }

        @Override
        public ForwardingDecision awaitDecision() {
            return decision;
        }

        @Override
        public void forwarded() {}

        @Override
        public void writeFailed() {}

        @Override
        public void abandoned() {}
    }
}
