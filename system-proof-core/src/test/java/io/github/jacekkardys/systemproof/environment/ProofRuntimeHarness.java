package io.github.jacekkardys.systemproof.environment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import io.github.jacekkardys.systemproof.control.SemanticInteractionSelector;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuard;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardSpec;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.proof.ProofExecution;
import io.github.jacekkardys.systemproof.proof.ProofPlan;
import io.github.jacekkardys.systemproof.proof.ProofPrerequisiteStatus;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Test-owned assembly retaining internal proof collaborators without production test seams. */
final class ProofRuntimeHarness implements AutoCloseable {
    final ManualDeadlineScheduler deadlines;
    final ProofExecutionCoordinator proofs;
    final ProofSubjectRegistry proofSubjects;
    final SemanticControlCoordinator controls;
    final EnvironmentEventPublisher events;
    final RuntimeConnectionRegistry connections;
    final EnvironmentExecution execution;
    final ProofTestFixture.RouteProvider route;
    final ConnectionId connectionId;
    final ProofSubjectRef subject;
    final CorrelationKey key;
    final CorrelationKey successorKey;

    private ProofRuntimeHarness(
        ProofOutcomeEvaluator evaluator,
        SemanticControlCoordinator.TimeoutScheduler controlTimeouts
    ) {
        deadlines = new ManualDeadlineScheduler();
        proofs = new ProofExecutionCoordinator(deadlines, evaluator);
        route = new ProofTestFixture.RouteProvider();
        ProofTestFixture.Client client = new ProofTestFixture.Client();
        ProofTestFixture.Server server = new ProofTestFixture.Server();
        EnvironmentTopology topology = EnvironmentTopology.of(
            List.of(client, server),
            List.of(ConnectionFactory.create(client.api, server.api))
        );
        EnvironmentLogging logging = EnvironmentLogging.defaults();
        ScenarioJournal journal = ScenarioJournal.withoutDiagnosticTime();
        JournalRenderer renderer = new JournalRenderer();
        events = new EnvironmentEventPublisher(
            journal,
            new JournalSlf4jEmitter(logging, renderer),
            proofs
        );
        proofSubjects = new ProofSubjectRegistry(events);
        SemanticControlCapabilityRegistry capabilities =
            new SemanticControlCapabilityRegistry();
        controls = new SemanticControlCoordinator(
            events,
            proofSubjects,
            capabilities,
            controlTimeouts,
            proofs
        );
        connections = new RuntimeConnectionRegistry(
            topology.connections(),
            events,
            ConnectionRouting.routed(
                ProofTestFixture.API,
                ProofTestFixture.PROFILE,
                route
            ),
            controls,
            proofSubjects,
            capabilities,
            proofs
        );
        proofs.bind(proofSubjects, controls, connections);
        ComponentExecutionPlan plan = ComponentExecutionPlan.create(
            topology.runtimeComponents(),
            topology::connectionFrom
        );
        RuntimeBindings bindings = new RuntimeBindings(connections);
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics(journal, renderer);
        EnvironmentLifecycle lifecycle = new EnvironmentLifecycle(events);
        ComponentRuntimeSupervisor components = new ComponentRuntimeSupervisor(
            plan,
            bindings,
            diagnostics,
            events
        );
        EnvironmentInspector inspector = new EnvironmentInspector(
            lifecycle,
            components,
            connections,
            diagnostics,
            journal,
            proofSubjects
        );
        execution = new EnvironmentExecution(
            lifecycle,
            components,
            connections,
            controls,
            proofs,
            proofSubjects,
            events,
            inspector
        );
        startExecution();
        connectionId = topology.connections().getFirst().id();
        subject = proofSubjects.create();
        key = key(1);
        successorKey = key(2);
        proofSubjects.arm(subject, key);
        proofSubjects.arm(subject, successorKey);
    }

    static ProofRuntimeHarness start() {
        return new ProofRuntimeHarness(
            ProofOutcomeEvaluator.failClosed(),
            new PassiveControlTimeoutScheduler()
        );
    }

    static ProofRuntimeHarness start(ProofOutcomeEvaluator evaluator) {
        return new ProofRuntimeHarness(
            evaluator,
            new PassiveControlTimeoutScheduler()
        );
    }

    static ProofRuntimeHarness startWithFailingControlScheduler() {
        return new ProofRuntimeHarness(
            ProofOutcomeEvaluator.failClosed(),
            new FailingControlTimeoutScheduler()
        );
    }

    SemanticPredecessorGuard declareGuard() {
        SemanticInteractionSelector<String> predecessor = selector("predecessor");
        return controls.declareGuard(SemanticPredecessorGuardSpec.requiring(
            subject,
            io.github.jacekkardys.systemproof.control.SemanticPredecessorRequirement
                .confirmed(predecessor),
            selector("successor"),
            Duration.ofSeconds(30)
        ));
    }

    SemanticHold declareHold(String expected) {
        return controls.declareHold(selector(expected), Duration.ofSeconds(30));
    }

    ProofExecution activate(ProofPlan plan) {
        return proofs.activate(plan, this::refreshObservation);
    }

    io.github.jacekkardys.systemproof.proof.ProofPrerequisite prerequisite() {
        return proofs.prerequisite(ProofPrerequisiteStatus.SATISFIED, null);
    }

    void publish(String value) {
        InteractionSession session = route.observations().openSession();
        io.github.jacekkardys.systemproof.observation.RecordedInteraction interaction =
            session.record(
                FlowDirection.CONSUMER_TO_PROVIDER,
                ProofTestFixture.TextCodec.INSTANCE,
                value
            );
        session.correlate(
            interaction.interactionRef(),
            CorrelationContribution.capture(
                "successor".equals(value) ? successorKey : key,
                ProofTestFixture.NativeCodec.INSTANCE,
                value
            )
        );
        try {
            io.github.jacekkardys.systemproof.observation.ForwardingPermit permit =
                route.coordinator().permit(interaction);
            if (permit.awaitDecision()
                == io.github.jacekkardys.systemproof.observation.ForwardingDecision.FORWARD) {
                permit.forwarded();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while publishing a proof fact", interrupted);
        }
    }

    void cleanupFailure() {
        proofs.fact(new io.github.jacekkardys.systemproof.journal.FailureEvent.ConnectionCleanup(
            connectionId,
            io.github.jacekkardys.systemproof.journal.FailureDetails.from(
                new CleanupFailure()
            )
        ));
    }

    void frameworkFailure() {
        proofs.journalFailure(new FrameworkFailure());
    }

    void gatewayFailure() {
        proofs.fact(new io.github.jacekkardys.systemproof.journal.FailureEvent
            .ConnectionMaterialization(
                connectionId,
                io.github.jacekkardys.systemproof.journal.FailureDetails.from(
                    new GatewayFailure()
                )
            ));
    }

    void adapterFailure() {
        route.failSampling(new AdapterFailure());
    }

    @Override
    public void close() {
        try {
            execution.close();
        } catch (IllegalStateException unfinished) {
            if (!unfinished.getMessage().contains("unfinished active proof execution")) {
                throw unfinished;
            }
        }
    }

    private SemanticInteractionSelector<String> selector(String expected) {
        return SemanticInteractionSelector.matching(
            connectionId,
            FlowDirection.CONSUMER_TO_PROVIDER,
            ProofTestFixture.TextCodec.INSTANCE,
            expected::equals
        ).forSubject(subject);
    }

    private void startExecution() {
        EnvironmentExecution.StartupFailure failure = execution.beginStart();
        if (failure != null) {
            throw new AssertionError("Test proof runtime failed to begin startup", failure.cause());
        }
        while (true) {
            EnvironmentExecution.StartStep step = execution.nextStartStep();
            if (step.failure() != null) {
                throw new AssertionError(
                    "Test proof runtime failed during startup",
                    step.failure().cause()
                );
            }
            if (step.complete()) {
                return;
            }
            RuntimeConnectionRegistry.ObservationResults results =
                step.observationBatch().evaluate();
            failure = execution.completeStartStep(results, null);
            if (failure != null) {
                throw new AssertionError(
                    "Test proof runtime failed to complete startup",
                    failure.cause()
                );
            }
        }
    }

    private void refreshObservation() {
        RuntimeConnectionRegistry.ObservationBatch batch =
            execution.observationRefreshBatch();
        execution.applyObservationRefresh(batch.evaluate());
    }

    private static CorrelationKey key(int seed) {
        byte[] digest = new byte[16];
        java.util.Arrays.fill(digest, (byte) seed);
        return CorrelationKey.ofDigest(
            new CorrelationKeySchema("system-proof-test", "proof-race", 1),
            digest
        );
    }

    static final class ManualDeadlineScheduler
        implements ProofExecutionCoordinator.DeadlineScheduler {
        private final AtomicReference<ScheduledDeadline> scheduled = new AtomicReference<>();

        @Override
        public ProofExecutionCoordinator.DeadlineTask schedule(
            Duration delay,
            Runnable action
        ) {
            ScheduledDeadline deadline = new ScheduledDeadline(action);
            if (!scheduled.compareAndSet(null, deadline)) {
                throw new IllegalStateException("Only one proof deadline is expected");
            }
            return deadline::cancel;
        }

        void fireRacingCallback() {
            java.util.Objects.requireNonNull(
                scheduled.get(),
                "proof deadline was not scheduled"
            ).fire();
        }

        @Override
        public void close() {}
    }

    private static final class ScheduledDeadline {
        private final Runnable action;

        private ScheduledDeadline(Runnable action) {
            this.action = java.util.Objects.requireNonNull(action, "action must not be null");
        }

        private void cancel() {
            // A scheduler callback already selected for execution may race with cancellation.
        }

        private void fire() {
            action.run();
        }
    }

    private static final class FrameworkFailure extends RuntimeException {}

    private static final class CleanupFailure extends RuntimeException {}

    private static final class GatewayFailure extends RuntimeException {}

    private static final class AdapterFailure extends RuntimeException {}

    private static final class FailingControlTimeoutScheduler
        implements SemanticControlCoordinator.TimeoutScheduler {
        @Override
        public SemanticControlCoordinator.TimeoutTask schedule(
            Duration delay,
            Runnable action
        ) {
            throw new ControlSchedulingFailure();
        }

        @Override
        public void close() {}
    }

    private static final class PassiveControlTimeoutScheduler
        implements SemanticControlCoordinator.TimeoutScheduler {
        @Override
        public SemanticControlCoordinator.TimeoutTask schedule(
            Duration delay,
            Runnable action
        ) {
            return () -> {};
        }

        @Override
        public void close() {}
    }

    private static final class ControlSchedulingFailure extends RuntimeException {}
}
