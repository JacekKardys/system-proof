package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;
import io.github.jacekkardys.systemproof.topology.PortRef;

class EnvironmentEventPublisherTest {
    private static final String SECRET = "publisher-canary-secret";
    private static final Component COMPONENT = new TestComponent();

    @Test
    void shouldAppendSafeRenderingBeforeThresholdedSlf4jEmission() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        List<String> emitted = new ArrayList<>();
        JournalSlf4jEmitter emitter = new JournalSlf4jEmitter(
            EnvironmentLogging.logs().defaultComponentLevel(LogLevel.WARN).build(),
            new JournalRenderer(),
            (level, line) -> {
                assertThat(journal.snapshot().entries()).hasSize(2);
                emitted.add(level + ":" + line);
            }
        );
        EnvironmentEventPublisher publisher = new EnvironmentEventPublisher(journal, emitter);

        publisher.component(
            COMPONENT,
            LogLevel.DEBUG,
            redacted("retained " + SECRET)
        );
        publisher.component(
            COMPONENT,
            LogLevel.ERROR,
            redacted("emitted " + SECRET)
        );

        assertThat(journal.snapshot().entries()).hasSize(2);
        assertThat(journal.snapshot().entries())
            .extracting(entry -> ((DiagnosticEvent) entry.event()).message().content())
            .containsExactly("retained [redacted]", "emitted [redacted]");
        assertThat(emitted).singleElement().asString()
            .contains("ERROR:T+00:00:00.000 [COMPONENT] [test] emitted [redacted]")
            .doesNotContain(SECRET);
    }

    @Test
    void shouldRetainOnlyTypeFromFailureBeforeJournalAndSlf4jPublication() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        List<String> emitted = new ArrayList<>();
        EnvironmentEventPublisher publisher = new EnvironmentEventPublisher(
            journal,
            new JournalSlf4jEmitter(
                EnvironmentLogging.defaults(),
                new JournalRenderer(),
                (level, line) -> emitted.add(line)
            )
        );
        RuntimeException failure = new RuntimeException(SECRET);
        failure.initCause(new IllegalStateException("cause-" + SECRET));
        failure.addSuppressed(new IllegalArgumentException("suppressed-" + SECRET));

        publisher.environmentStartupFailure(failure);

        FailureEvent.EnvironmentStartup stored = (FailureEvent.EnvironmentStartup)
            journal.snapshot().entries().getFirst().event();
        assertThat(stored.failure().failureType()).isEqualTo("RuntimeException");
        assertThat(stored.toString()).doesNotContain(SECRET);
        assertThat(emitted).singleElement().asString()
            .contains("Environment startup failed: RuntimeException")
            .doesNotContain(SECRET);
        assertThat(stored.failure().getClass().getDeclaredFields())
            .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType()));
    }

    private static RedactedDiagnosticText redacted(String input) {
        return RedactedDiagnosticText.redact(
            input,
            bounded -> bounded.replace(SECRET, "[redacted]")
        );
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class TestComponent implements Component {
        @Override
        public ComponentId id() {
            return ComponentId.component(ComponentType.of("test"));
        }

        @Override
        public ComponentType type() {
            return ComponentType.of("test");
        }

        @Override
        public RuntimeConfig configuration() {
            return new EmptyConfig();
        }

        @Override
        public List<PortRef> ports() {
            return List.of();
        }
    }
}
