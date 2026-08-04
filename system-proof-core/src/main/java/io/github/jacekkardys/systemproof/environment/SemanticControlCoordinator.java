package io.github.jacekkardys.systemproof.environment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import io.github.jacekkardys.systemproof.control.SemanticHoldSelector;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.observation.ForwardingDecision;
import io.github.jacekkardys.systemproof.observation.ForwardingPermit;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.RecordedInteraction;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;

/** Environment-owned semantic-control registry, matcher, and linearizable state machine. */
final class SemanticControlCoordinator
    implements SemanticControls, InteractionDecisionCoordinator {

    private static final long FIRST_HOLD_VALUE = 1L;
    private static final ForwardingPermit IMMEDIATE_FORWARD =
        new TerminalPermit(ForwardingDecision.FORWARD);
    private static final ForwardingPermit CLOSE_SESSION =
        new TerminalPermit(ForwardingDecision.CLOSE_SESSION);

    private final Object owner = new Object();
    private final EnvironmentEventPublisher events;
    private final ProofSubjectRegistry proofSubjects;
    private final TimeoutScheduler timeoutScheduler;
    private final Map<RuntimeSemanticHoldRef, HoldEntry> active = new LinkedHashMap<>();
    private long nextHoldValue = FIRST_HOLD_VALUE;
    private boolean accepting = true;

    SemanticControlCoordinator(
        EnvironmentEventPublisher events,
        ProofSubjectRegistry proofSubjects
    ) {
        this(events, proofSubjects, new SystemTimeoutScheduler());
    }

    SemanticControlCoordinator(
        EnvironmentEventPublisher events,
        ProofSubjectRegistry proofSubjects,
        TimeoutScheduler timeoutScheduler
    ) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.proofSubjects = Objects.requireNonNull(
            proofSubjects,
            "proofSubjects must not be null"
        );
        this.timeoutScheduler = Objects.requireNonNull(
            timeoutScheduler,
            "timeoutScheduler must not be null"
        );
    }

    @Override
    public <T> SemanticHold arm(
        SemanticHoldSelector<T> selector,
        Duration maximumHoldDuration
    ) {
        selector = Objects.requireNonNull(selector, "selector must not be null");
        maximumHoldDuration = requirePositive(
            maximumHoldDuration,
            "maximumHoldDuration"
        );
        synchronized (this) {
            requireAccepting();
            selector.proofSubject().ifPresent(proofSubjects::validateSubject);
            RuntimeSemanticHoldRef ref = nextReference();
            HoldEntry entry = new HoldEntry(ref, selector, maximumHoldDuration);
            active.put(ref, entry);
            append(entry, SemanticHoldState.ARMED, Optional.empty());
            return new SemanticHoldHandle(this, entry);
        }
    }

    @Override
    public ForwardingDecision decide(InteractionRef interactionRef) {
        Objects.requireNonNull(interactionRef, "interactionRef must not be null");
        throw new UnsupportedOperationException(
            "Semantic controls require a recorded interaction with captured evidence"
        );
    }

    @Override
    public ForwardingPermit permit(RecordedInteraction interaction) {
        interaction = Objects.requireNonNull(interaction, "interaction must not be null");
        List<Runnable> afterTransition = new ArrayList<>();
        ForwardingPermit decision;
        synchronized (this) {
            decision = decideLocked(interaction, afterTransition);
        }
        runAfterTransition(afterTransition);
        return decision;
    }

    private ForwardingPermit decideLocked(
        RecordedInteraction interaction,
        List<Runnable> afterTransition
    ) {
        if (!accepting) {
            return CLOSE_SESSION;
        }

        List<HoldEntry> matches = new ArrayList<>();
        for (HoldEntry entry : active.values()) {
            if (entry.state != SemanticHoldState.ARMED
                || !entry.connectionId.equals(interaction.interactionRef().connectionId())
                || entry.direction != interaction.interactionRef().direction()
                || !entry.evidenceSchema.equals(interaction.evidence().schemaId())) {
                continue;
            }
            boolean evidenceMatches;
            try {
                evidenceMatches = entry.selector.matchesEvidence(interaction.evidence());
            } catch (RuntimeException | Error failure) {
                entry.interactionRef = interaction.interactionRef();
                failLocked(
                    entry,
                    SemanticHoldFailure.SELECTOR_EVALUATION,
                    afterTransition
                );
                return CLOSE_SESSION;
            }
            if (!evidenceMatches) {
                continue;
            }
            if (entry.proofSubject.isPresent()
                && !proofSubjects.isUniquelyCorrelated(
                    entry.proofSubject.orElseThrow(),
                    interaction.interactionRef()
                )) {
                continue;
            }
            matches.add(entry);
        }

        if (matches.isEmpty()) {
            return IMMEDIATE_FORWARD;
        }
        if (matches.size() > 1) {
            for (HoldEntry entry : matches) {
                entry.interactionRef = interaction.interactionRef();
                failLocked(
                    entry,
                    SemanticHoldFailure.AMBIGUOUS_MATCH,
                    afterTransition
                );
            }
            return CLOSE_SESSION;
        }

        HoldEntry matched = matches.getFirst();
        matched.interactionRef = interaction.interactionRef();
        transitionLocked(matched, SemanticHoldState.REACHED_HELD, Optional.empty());
        HeldForwardingPermit permit = new HeldForwardingPermit(this, matched);
        matched.permit = permit;
        try {
            matched.timeoutTask = timeoutScheduler.schedule(
                matched.maximumHoldDuration,
                () -> timeout(matched)
            );
        } catch (RuntimeException | Error schedulingFailure) {
            failLocked(matched, SemanticHoldFailure.INTERNAL_FAILURE, afterTransition);
            return CLOSE_SESSION;
        }
        afterTransition.add(() -> matched.reached.complete(matched.interactionRef));
        return permit;
    }

    private SemanticHoldState state(HoldEntry entry) {
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
            transitionLocked(entry, SemanticHoldState.RELEASING, Optional.empty());
            cancelTimeout(entry);
            afterTransition.add(
                () -> entry.permit.authorize(ForwardingDecision.FORWARD)
            );
            result = entry.releaseCompletion.minimalCompletionStage();
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
            terminalLocked(
                entry,
                SemanticHoldState.CANCELLED,
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
            terminalLocked(
                entry,
                SemanticHoldState.TIMED_OUT,
                Optional.empty(),
                afterTransition
            );
        }
        runAfterTransition(afterTransition);
    }

    private void forwarded(HoldEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            requireState(entry, SemanticHoldState.RELEASING, "report forwarding success");
            terminalLocked(
                entry,
                SemanticHoldState.FORWARDED,
                Optional.empty(),
                afterTransition
            );
        }
        runAfterTransition(afterTransition);
    }

    private void writeFailed(HoldEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            requireState(entry, SemanticHoldState.RELEASING, "report write failure");
            failLocked(entry, SemanticHoldFailure.WRITE_FAILURE, afterTransition);
        }
        runAfterTransition(afterTransition);
    }

    private void abandoned(HoldEntry entry) {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (entry.state != SemanticHoldState.REACHED_HELD
                && entry.state != SemanticHoldState.RELEASING) {
                return;
            }
            failLocked(entry, SemanticHoldFailure.SESSION_ABANDONED, afterTransition);
        }
        runAfterTransition(afterTransition);
    }

    void completeExecution() {
        List<Runnable> afterTransition = new ArrayList<>();
        synchronized (this) {
            if (!accepting) {
                return;
            }
            accepting = false;
            for (HoldEntry entry : List.copyOf(active.values())) {
                if (entry.state == SemanticHoldState.ARMED
                    || entry.state == SemanticHoldState.REACHED_HELD) {
                    terminalLocked(
                        entry,
                        SemanticHoldState.CANCELLED,
                        Optional.empty(),
                        afterTransition
                    );
                }
            }
        }
        runAfterTransition(afterTransition);
        timeoutScheduler.close();
    }

    private void failLocked(
        HoldEntry entry,
        SemanticHoldFailure failure,
        List<Runnable> afterTransition
    ) {
        terminalLocked(
            entry,
            SemanticHoldState.FAILED,
            Optional.of(Objects.requireNonNull(failure, "failure must not be null")),
            afterTransition
        );
    }

    private void terminalLocked(
        HoldEntry entry,
        SemanticHoldState terminalState,
        Optional<SemanticHoldFailure> failure,
        List<Runnable> afterTransition
    ) {
        transitionLocked(entry, terminalState, failure);
        cancelTimeout(entry);
        active.remove(entry.ref);
        entry.selector = null;
        if (entry.permit != null
            && terminalState != SemanticHoldState.FORWARDED) {
            afterTransition.add(
                () -> entry.permit.authorize(ForwardingDecision.CLOSE_SESSION)
            );
        }
        IllegalStateException terminalFailure = terminalFailure(entry);
        if (!entry.reached.isDone()) {
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

    private void transitionLocked(
        HoldEntry entry,
        SemanticHoldState next,
        Optional<SemanticHoldFailure> failure
    ) {
        entry.state = Objects.requireNonNull(next, "next must not be null");
        append(entry, next, failure);
    }

    private void append(
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

    private RuntimeSemanticHoldRef nextReference() {
        if (nextHoldValue < FIRST_HOLD_VALUE) {
            throw new IllegalStateException(
                "Semantic-hold identity space is exhausted for this environment execution"
            );
        }
        RuntimeSemanticHoldRef ref = new RuntimeSemanticHoldRef(owner, nextHoldValue);
        nextHoldValue = nextHoldValue == Long.MAX_VALUE
            ? Long.MIN_VALUE
            : nextHoldValue + 1L;
        return ref;
    }

    private void requireAccepting() {
        if (!accepting) {
            throw new IllegalStateException(
                "Environment execution is complete and cannot arm semantic holds"
            );
        }
    }

    private static void requireState(
        HoldEntry entry,
        SemanticHoldState expected,
        String action
    ) {
        if (entry.state != expected) {
            throw new IllegalStateException(
                "Cannot " + action + " from semantic hold state " + entry.state
            );
        }
    }

    private static void cancelTimeout(HoldEntry entry) {
        if (entry.timeoutTask != null) {
            entry.timeoutTask.cancel();
            entry.timeoutTask = null;
        }
    }

    private static IllegalStateException terminalFailure(HoldEntry entry) {
        return new IllegalStateException(
            "Semantic hold completed with state " + entry.state
        );
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
                    .name("system-proof-semantic-hold-timeouts")
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
        private final io.github.jacekkardys.systemproof.topology.ConnectionId connectionId;
        private final io.github.jacekkardys.systemproof.observation.FlowDirection direction;
        private final io.github.jacekkardys.systemproof.observation.EvidenceSchemaId evidenceSchema;
        private final Optional<ProofSubjectRef> proofSubject;
        private final CompletableFuture<InteractionRef> reached = new CompletableFuture<>();
        private final CompletableFuture<SemanticHoldState> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> releaseCompletion = new CompletableFuture<>();
        private SemanticHoldSelector<?> selector;
        private SemanticHoldState state = SemanticHoldState.ARMED;
        private InteractionRef interactionRef;
        private HeldForwardingPermit permit;
        private TimeoutTask timeoutTask;

        private HoldEntry(
            RuntimeSemanticHoldRef ref,
            SemanticHoldSelector<?> selector,
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

    private static final class HeldForwardingPermit implements ForwardingPermit {
        private final SemanticControlCoordinator coordinator;
        private final HoldEntry entry;
        private final CompletableFuture<ForwardingDecision> decision =
            new CompletableFuture<>();

        private HeldForwardingPermit(
            SemanticControlCoordinator coordinator,
            HoldEntry entry
        ) {
            this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
            );
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
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
            coordinator.forwarded(entry);
        }

        @Override
        public void writeFailed() {
            coordinator.writeFailed(entry);
        }

        @Override
        public void abandoned() {
            coordinator.abandoned(entry);
        }
    }

    private record RuntimeSemanticHoldRef(Object owner, long value)
        implements SemanticHoldRef {
        private RuntimeSemanticHoldRef {
            Objects.requireNonNull(owner, "owner must not be null");
            if (value < FIRST_HOLD_VALUE) {
                throw new IllegalArgumentException(
                    "semantic-hold value must be at least " + FIRST_HOLD_VALUE
                );
            }
        }

        @Override
        public String toString() {
            return "semantic-hold-" + value;
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
