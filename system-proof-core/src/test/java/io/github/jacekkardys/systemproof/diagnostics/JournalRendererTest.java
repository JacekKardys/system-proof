package io.github.jacekkardys.systemproof.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.ComponentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.ConnectionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.JournalSequence;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.ProofSubjectArmedEvent;
import io.github.jacekkardys.systemproof.journal.ProofSubjectCreatedEvent;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.proof.CorrelationCardinality;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;

class JournalRendererTest {
    private final JournalRenderer renderer = new JournalRenderer();

    @Test
    void shouldRenderMultilineEntriesWithTheSamePrefixAndOptionalTime() {
        ScenarioJournalSnapshot snapshot = snapshot(List.of(
            entry(1, Optional.of(Duration.ofMillis(250)), diagnostic("first\nsecond")),
            entry(2, Optional.empty(), diagnostic("without time"))
        ));

        assertThat(renderer.render(snapshot)).isEqualTo(
            "T+00:00:00.250 [FRAMEWORK] [environment] first"
                + System.lineSeparator()
                + "T+00:00:00.250 [FRAMEWORK] [environment] second"
                + System.lineSeparator()
                + "T+--:--:--.--- [FRAMEWORK] [environment] without time"
        );
    }

    @Test
    void shouldRenderAnUnknownOpenScenarioEventWithoutInspectingItsPayload() {
        AtomicInteger toStringCalls = new AtomicInteger();
        JournalEntry entry = entry(1, new ClientScenarioEvent("not-rendered", toStringCalls));

        assertThat(renderer.renderLines(entry))
            .singleElement()
            .asString()
            .contains("[EVENT] [ClientScenarioEvent]")
            .contains("Recorded scenario event type=")
            .doesNotContain("not-rendered");
        assertThat(toStringCalls).hasValue(0);
    }

    @Test
    void shouldFilterComponentEntriesByStructuredIdentity() {
        ComponentId first = ComponentId.component(ComponentType.of("service"), "first");
        ComponentId second = ComponentId.component(ComponentType.of("service"), "second");
        ScenarioJournalSnapshot snapshot = snapshot(List.of(
            entry(1, new ComponentLifecycleEvent(first, ComponentState.STARTING)),
            entry(2, new DiagnosticEvent(
                new DiagnosticEvent.ComponentSubject(first),
                LogLevel.INFO,
                redacted("first output")
            )),
            entry(3, new DiagnosticEvent(
                new DiagnosticEvent.ComponentSubject(second),
                LogLevel.INFO,
                redacted("second output")
            )),
            entry(4, new CheckpointEvent(
                second,
                new CheckpointId("other"),
                CheckpointEvent.Kind.CHECKPOINT,
                CheckpointEvent.Stage.OBSERVED
            ))
        ));

        assertThat(renderer.renderComponent(snapshot, first))
            .contains("[COMPONENT] [service-first] Starting component", "first output")
            .doesNotContain("service-second", "second output", "other");
    }

    @Test
    void shouldRenderEveryClosedEventVariantWithoutSensitiveEvidence() {
        ComponentId component = ComponentId.component(ComponentType.of("service"));
        ConnectionDescriptor connection = connection();
        ConnectionId connectionId = connection.id();
        FailureDetails failure = FailureDetails.from(new IllegalStateException("broken"));
        EvidenceSnapshot evidence = evidence();
        InteractionRef interaction = interactionRef(connectionId);
        ProofSubjectRef subject = new ProofSubjectRef() {
            @Override
            public String toString() {
                return "proof-subject-1";
            }
        };
        CorrelationKey key = CorrelationKey.ofDigest(
            new CorrelationKeySchema("test", "request", 1),
            new byte[16]
        );
        List<ScenarioEvent> events = List.of(
            new EnvironmentLifecycleEvent(EnvironmentState.FAILED),
            new ComponentLifecycleEvent(component, ComponentState.FAILED),
            new ConnectionLifecycleEvent(
                connection,
                ConnectionState.RUNNING,
                RoutingMode.DIRECT,
                true,
                true
            ),
            diagnostic("diagnostic"),
            new FailureEvent.EnvironmentStartup(failure),
            new FailureEvent.ComponentStartup(component, failure),
            new FailureEvent.ComponentCleanup(component, failure),
            new FailureEvent.ConnectionMaterialization(connectionId, failure),
            new FailureEvent.ConnectionCleanup(connectionId, failure),
            new FailureEvent.DriverResourceCleanup("shared-resource", failure),
            new InteractionObservationEvent(interaction, evidence),
            new ProofSubjectCreatedEvent(subject),
            new ProofSubjectArmedEvent(subject, key, false),
            new CorrelationCandidateEvent(
                Optional.of(subject),
                key,
                interaction,
                evidence,
                CorrelationCardinality.UNIQUE
            ),
            new CheckpointEvent(
                component,
                new CheckpointId("request-visible"),
                CheckpointEvent.Kind.BARRIER,
                CheckpointEvent.Stage.OBSERVED
            ),
            new DisruptionLifecycleEvent(
                component,
                new DisruptionId("latency-window"),
                DisruptionLifecycleEvent.Stage.ACTIVE
            )
        );
        ScenarioJournalSnapshot snapshot = snapshot(IntStream.range(0, events.size())
            .mapToObj(index -> entry(index + 1L, events.get(index)))
            .toList());

        assertThat(renderer.render(snapshot))
            .contains(
                "Environment failed",
                "Component failed",
                "Consumer target available",
                "diagnostic",
                "Environment startup failed: IllegalStateException",
                "Component startup failed: IllegalStateException",
                "Component cleanup failed: IllegalStateException",
                "Connection materialization failed: IllegalStateException",
                "Connection cleanup failed: IllegalStateException",
                "Driver resource 'shared-resource' cleanup failed",
                "Observed typed evidence schema=test.external:interaction version=1",
                "Created proof subject",
                "Armed proof subject keySchema=test:request:v1 sharedKey=false",
                "Published correlation candidate keySchema=test:request:v1",
                "Recorded barrier stage=OBSERVED",
                "Recorded disruption stage=ACTIVE"
            )
            .doesNotContain("sensitive-binary", "secret-attribute", "[B@");
    }

    @Test
    void shouldRenderLargeMultilineHistoryInExactStorageOrder() {
        int entryCount = 1_000;
        List<JournalEntry> entries = IntStream.range(0, entryCount)
            .mapToObj(index -> entry(
                index + 1L,
                diagnostic("entry-" + index + "-first\nentry-" + index + "-second")
            ))
            .toList();

        String rendered = renderer.render(snapshot(entries));
        List<String> lines = rendered.lines().toList();

        assertThat(lines).hasSize(entryCount * 2);
        for (int index = 0; index < entryCount; index++) {
            assertThat(lines.get(index * 2)).endsWith("entry-" + index + "-first");
            assertThat(lines.get(index * 2 + 1)).endsWith("entry-" + index + "-second");
        }
        assertThat(lines.getFirst()).endsWith("entry-0-first");
        assertThat(lines.get(1)).endsWith("entry-0-second");
        assertThat(lines.get(lines.size() - 2)).endsWith("entry-999-first");
        assertThat(lines.getLast()).endsWith("entry-999-second");
    }

    @Test
    void shouldBoundTheCompleteRenderedJournal() {
        List<JournalEntry> entries = IntStream.range(0, 100)
            .mapToObj(index -> entry(index + 1L, diagnostic("x".repeat(4 * 1024))))
            .toList();

        String rendered = renderer.render(snapshot(entries));

        assertThat(rendered)
            .hasSizeLessThanOrEqualTo(256 * 1024)
            .endsWith("[DIAGNOSTICS TRUNCATED]");
    }

    private static ScenarioJournalSnapshot snapshot(List<JournalEntry> entries) {
        return new ScenarioJournalSnapshot(entries);
    }

    private static JournalEntry entry(long sequence, ScenarioEvent event) {
        return entry(sequence, Optional.of(Duration.ZERO), event);
    }

    private static JournalEntry entry(
        long sequence,
        Optional<Duration> elapsed,
        ScenarioEvent event
    ) {
        return new JournalEntry(new JournalSequence(sequence), elapsed, event);
    }

    private static DiagnosticEvent diagnostic(String message) {
        return new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            redacted(message)
        );
    }

    private static RedactedDiagnosticText redacted(String message) {
        return RedactedDiagnosticText.redact(message, input -> input);
    }

    private static ConnectionDescriptor connection() {
        return ConnectionDescriptor.of(
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
    }

    private static InteractionRef interactionRef(ConnectionId connectionId) {
        return new InteractionRef(
            new SessionId(connectionId, SessionId.FIRST_VALUE),
            FlowDirection.CONSUMER_TO_PROVIDER,
            InteractionRef.FIRST_ORDINAL
        );
    }

    private static EvidenceSnapshot evidence() {
        return EvidenceSnapshot.capture(
            MutableInteractionEvidence.codec(),
            new MutableInteractionEvidence(
                "sensitive-binary".getBytes(StandardCharsets.UTF_8),
                new ArrayList<>(List.of("secret-attribute"))
            )
        );
    }

    private static final class ClientScenarioEvent implements ScenarioEvent {
        private final String payload;
        private final AtomicInteger toStringCalls;

        private ClientScenarioEvent(String payload, AtomicInteger toStringCalls) {
            this.payload = payload;
            this.toStringCalls = toStringCalls;
        }

        @Override
        public String toString() {
            toStringCalls.incrementAndGet();
            return payload;
        }
    }
}
