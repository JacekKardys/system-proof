package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.testsupport.OpaqueReferenceDiagnosticsFixture;
import io.github.jacekkardys.systemproof.testsupport.OpaqueReferenceDiagnosticsFixture.Behavior;
import io.github.jacekkardys.systemproof.testsupport.OpaqueReferenceDiagnosticsFixture.Probe;

class JournalOpaqueReferenceSafetyTest {
    @Test
    void shouldKeepSlf4jAndEnvironmentDiagnosticsIndependentOfOpaqueToString() {
        String[] canaries = OpaqueReferenceDiagnosticsFixture.allCanaries()
            .toArray(String[]::new);
        for (Behavior behavior : Behavior.values()) {
            Probe probe = new Probe(behavior);
            ScenarioJournal journal = ScenarioJournal.withoutDiagnosticTime();
            List<String> emitted = new ArrayList<>();
            JournalRenderer renderer = new JournalRenderer();
            JournalSlf4jEmitter emitter = new JournalSlf4jEmitter(
                EnvironmentLogging.defaults(),
                renderer,
                (level, line) -> emitted.add(line)
            );
            List<ScenarioEvent> events = OpaqueReferenceDiagnosticsFixture.frameworkEvents(
                probe
            );
            for (ScenarioEvent event : events) {
                JournalEntry entry = journal.append(event);
                emitter.framework(entry, LogLevel.INFO);
            }

            RuntimeDiagnostics runtime = new RuntimeDiagnostics(journal, renderer);
            EnvironmentDiagnostics diagnostics = runtime.render(runtime.snapshot(
                    EnvironmentState.RUNNING,
                    List.of(),
                    ignored -> { throw new AssertionError("No component state expected"); },
                    List.of()
                ));

            assertThat(emitted)
                .hasSize(events.size())
                .allSatisfy(line -> assertThat(line)
                    .doesNotContain(canaries)
                    .doesNotContain("injected journal line"));
            assertThat(diagnostics.content())
                .contains("[ref=opaque]", "[subject=assigned]")
                .doesNotContain(canaries)
                .doesNotContain("injected journal line");
            assertThat(probe.toStringCalls()).isZero();
        }
    }
}
