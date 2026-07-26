package io.github.jacekkardys.systemproof.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.InteractionRef;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.SessionId;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.ConnectionState;
import io.github.jacekkardys.systemproof.model.EnvironmentState;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RoutingMode;

class EnvironmentEventLogTest {
    @Test
    void shouldRenderTheReadableLifecycleFromOneMonotonicDiagnosticTimeline() {
        AtomicLong clock = new AtomicLong();
        ScenarioJournal journal = new ScenarioJournal(clock::get);
        EnvironmentEventLog eventLog = view(journal);

        clock.set(TimeUnit.MILLISECONDS.toNanos(250));
        eventLog.environmentLifecycle(EnvironmentState.STARTING);
        clock.set(TimeUnit.MILLISECONDS.toNanos(1_195));
        eventLog.environmentLifecycle(EnvironmentState.RUNNING);

        assertThat(eventLog.snapshot().content()).isEqualTo(
            "T+00:00:00.250 [FRAMEWORK] [environment] Starting environment"
                + System.lineSeparator()
                + "T+00:00:01.195 [FRAMEWORK] [environment] Environment started"
        );
    }

    @Test
    void shouldRenderOnlyTheSuppliedImmutableSnapshot() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = view(journal);
        eventLog.framework(LogLevel.INFO, "captured");
        var captured = journal.snapshot();

        eventLog.framework(LogLevel.INFO, "later");

        assertThat(eventLog.render(captured).content())
            .contains("captured")
            .doesNotContain("later");
        assertThat(eventLog.snapshot().content()).contains("captured", "later");
    }

    @Test
    void shouldUseOneJournalWhenSeveralViewsRenderTheSameHistory() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog first = view(journal);
        EnvironmentEventLog second = view(journal);

        first.framework(LogLevel.INFO, "first view");
        second.framework(LogLevel.INFO, "second view");
        var snapshot = journal.snapshot();

        assertThat(journal.snapshot().entries()).hasSize(2);
        assertThat(first.render(snapshot)).isEqualTo(second.render(snapshot));
        assertThat(first.render(snapshot).content()).contains("first view", "second view");
    }

    @Test
    void shouldFilterComponentEventsByStructuredComponentIdentity() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = view(journal);
        ComponentId first = ComponentId.component(ComponentType.of("service"), "first");
        ComponentId second = ComponentId.component(ComponentType.of("service"), "second");
        journal.append(new ComponentLifecycleEvent(first, ComponentState.STARTING));
        journal.append(new DiagnosticEvent(
            new DiagnosticEvent.ComponentSubject(first),
            LogLevel.INFO,
            "first output"
        ));
        journal.append(new DiagnosticEvent(
            new DiagnosticEvent.ComponentSubject(second),
            LogLevel.INFO,
            "second output"
        ));
        EvidenceSnapshot evidence = EvidenceSnapshot.capture(
            MutableInteractionEvidence.codec(),
            new MutableInteractionEvidence(new byte[] {1}, new ArrayList<>())
        );
        ConnectionId connectionId =
            ConnectionId.of("client[].api->server[].api");
        journal.append(new InteractionObservationEvent(
            interactionRef(connectionId),
            evidence
        ));
        journal.append(new CheckpointEvent(
            second,
            new CheckpointId("second-checkpoint"),
            CheckpointEvent.Kind.CHECKPOINT,
            CheckpointEvent.Stage.OBSERVED
        ));
        var snapshot = journal.snapshot();

        assertThat(eventLog.componentSnapshot(snapshot, first))
            .contains(
                "[COMPONENT] [service-first] Starting component",
                "first output"
            )
            .doesNotContain(
                "service-second",
                "second output",
                "second-checkpoint",
                "[INTERACTION]"
            );
    }

    @Test
    void shouldRetainEventsBelowTheEmissionThresholdForFailureDiagnostics() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = new EnvironmentEventLog(
            journal,
            EnvironmentLogging.logs().frameworkLevel(LogLevel.WARN).build()
        );

        eventLog.framework(LogLevel.DEBUG, "Configuration details");
        eventLog.framework(LogLevel.INFO, "Startup progress");

        assertThat(eventLog.snapshot().content())
            .contains("Configuration details")
            .contains("Startup progress");
        assertThat(journal.snapshot().entries()).hasSize(2);
    }

    @Test
    void shouldKeepMultilineDiagnosticsReadable() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = view(journal);

        eventLog.framework(
            LogLevel.INFO,
            "bootstrap line one" + System.lineSeparator() + "bootstrap line two"
        );

        assertThat(eventLog.snapshot().content()).isEqualTo(
            "T+00:00:00.000 [FRAMEWORK] [environment] bootstrap line one"
                + System.lineSeparator()
                + "T+00:00:00.000 [FRAMEWORK] [environment] bootstrap line two"
        );
        assertThat(journal.snapshot().entries()).hasSize(1);
    }

    @Test
    void shouldFreezeMutableThrowableDataBeforeAppendingTheFailure() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = view(journal);
        MutableMessageFailure failure = new MutableMessageFailure("original failure");

        eventLog.environmentStartupFailure(failure);
        var captured = journal.snapshot();
        failure.changeMessage("mutated failure");

        FailureEvent.EnvironmentStartup stored =
            (FailureEvent.EnvironmentStartup) captured.entries().getFirst().event();
        assertThat(stored.failure().message()).contains("original failure");
        assertThat(eventLog.render(captured).content())
            .contains("original failure")
            .doesNotContain("mutated failure");
    }

    @Test
    void shouldDeliberatelyRenderEveryAcceptedEventKind() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = view(journal);
        ComponentId component = ComponentId.component(ComponentType.of("service"));
        ConnectionDescriptor connection = ConnectionDescriptor.of(
            ComponentId.component(ComponentType.of("client")),
            "api",
            ComponentId.component(ComponentType.of("server")),
            "api",
            "api",
            String.class.getName(),
            "invocation",
            "http",
            "http"
        );
        ConnectionId connectionId = connection.id();
        FailureDetails failure = FailureDetails.from(new IllegalStateException("broken"));

        journal.append(new EnvironmentLifecycleEvent(EnvironmentState.FAILED));
        journal.append(new ComponentLifecycleEvent(component, ComponentState.FAILED));
        journal.append(new ConnectionLifecycleEvent(
            connection,
            ConnectionState.RUNNING,
            RoutingMode.DIRECT,
            true,
            true
        ));
        journal.append(new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.WARN,
            "diagnostic"
        ));
        journal.append(new FailureEvent.EnvironmentStartup(failure));
        journal.append(new FailureEvent.ComponentStartup(component, failure));
        journal.append(new FailureEvent.ComponentCleanup(component, failure));
        journal.append(new FailureEvent.ConnectionMaterialization(connectionId, failure));
        journal.append(new FailureEvent.ConnectionCleanup(connectionId, failure));
        journal.append(new FailureEvent.DriverResourceCleanup("shared-resource", failure));
        EvidenceSnapshot evidence = EvidenceSnapshot.capture(
            MutableInteractionEvidence.codec(),
            new MutableInteractionEvidence(
                "sensitive-binary".getBytes(StandardCharsets.UTF_8),
                new ArrayList<>(List.of("secret-attribute"))
            )
        );
        journal.append(new InteractionObservationEvent(
            interactionRef(connectionId),
            evidence
        ));
        journal.append(new CheckpointEvent(
            component,
            new CheckpointId("request-visible"),
            CheckpointEvent.Kind.BARRIER,
            CheckpointEvent.Stage.OBSERVED
        ));
        journal.append(new DisruptionLifecycleEvent(
            component,
            new DisruptionId("latency-window"),
            DisruptionLifecycleEvent.Stage.ACTIVE
        ));

        assertThat(eventLog.snapshot().content())
            .contains(
                "Environment failed",
                "Component failed",
                "[CONNECTION] [client[].api->server[].api] Consumer target available "
                    + "source=client[].api target=server[].api contract=api "
                    + "contractType=java.lang.String interaction=invocation "
                    + "protocol=http scheme=http state=RUNNING mode=DIRECT "
                    + "directTargetAvailable=true consumerTargetAvailable=true",
                "diagnostic",
                "Environment startup failed: IllegalStateException - broken",
                "Component startup failed: IllegalStateException - broken",
                "Component cleanup failed: IllegalStateException - broken",
                "Connection materialization failed: IllegalStateException - broken",
                "Connection cleanup failed: IllegalStateException - broken",
                "Driver resource 'shared-resource' cleanup failed: "
                    + "IllegalStateException - broken",
                "[INTERACTION] [connection=client[].api->server[].api] "
                    + "[session=client[].api->server[].api/session-1] "
                    + "[flow=CONSUMER_TO_PROVIDER] [ordinal=1] "
                    + "[ref=client[].api->server[].api/session-1/"
                    + "CONSUMER_TO_PROVIDER/1] Observed typed evidence "
                    + "schema=test.external:interaction version=1 encodedBytes=",
                "[CHECKPOINT] [service] [request-visible] "
                    + "Recorded barrier stage=OBSERVED",
                "[DISRUPTION] [service] [latency-window] "
                    + "Recorded disruption stage=ACTIVE"
            )
            .doesNotContain(
                "EnvironmentLifecycleEvent[",
                "ComponentLifecycleEvent[",
                "ConnectionLifecycleEvent[",
                "DiagnosticEvent[",
                "FailureDetails[",
                "InteractionObservationEvent[",
                "CheckpointEvent[",
                "DisruptionLifecycleEvent[",
                "sensitive-binary",
                "secret-attribute"
            );
    }

    private static EnvironmentEventLog view(ScenarioJournal journal) {
        return new EnvironmentEventLog(journal, EnvironmentLogging.defaults());
    }

    private static InteractionRef interactionRef(ConnectionId connectionId) {
        return new InteractionRef(
            new SessionId(connectionId, SessionId.FIRST_VALUE),
            FlowDirection.CONSUMER_TO_PROVIDER,
            InteractionRef.FIRST_ORDINAL
        );
    }

    private static final class MutableMessageFailure extends RuntimeException {
        private String currentMessage;

        private MutableMessageFailure(String message) {
            currentMessage = message;
        }

        private void changeMessage(String message) {
            currentMessage = message;
        }

        @Override
        public String getMessage() {
            return currentMessage;
        }
    }
}
