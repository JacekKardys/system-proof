package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.FailureEvent;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

class EnvironmentEventPublisherTest {
    @Test
    void shouldAppendBeforeThresholdedSlf4jEmission() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        List<String> emitted = new ArrayList<>();
        JournalSlf4jEmitter emitter = new JournalSlf4jEmitter(
            EnvironmentLogging.logs().frameworkLevel(LogLevel.WARN).build(),
            new JournalRenderer(),
            (level, line) -> {
                assertThat(journal.snapshot().entries()).hasSize(2);
                emitted.add(level + ":" + line);
            }
        );
        EnvironmentEventPublisher publisher = publisher(journal, new FailureRedactor(), emitter);

        publisher.framework(LogLevel.DEBUG, "retained below threshold");
        publisher.framework(LogLevel.ERROR, "emitted after append");

        assertThat(journal.snapshot().entries()).hasSize(2);
        assertThat(journal.snapshot().entries())
            .extracting(entry -> ((DiagnosticEvent) entry.event()).message())
            .containsExactly("retained below threshold", "emitted after append");
        assertThat(emitted).singleElement().asString()
            .contains("ERROR:T+00:00:00.000 [FRAMEWORK] [environment] emitted after append");
    }

    @Test
    void shouldTreatOffAsEmissionOnlyAndNeverAsAHistoryFilter() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        List<String> emitted = new ArrayList<>();
        JournalSlf4jEmitter emitter = new JournalSlf4jEmitter(
            EnvironmentLogging.defaults(),
            new JournalRenderer(),
            (level, line) -> emitted.add(line)
        );
        EnvironmentEventPublisher publisher = publisher(journal, new FailureRedactor(), emitter);

        publisher.framework(LogLevel.OFF, "stored only");

        assertThat(journal.snapshot().entries()).hasSize(1);
        assertThat(emitted).isEmpty();
    }

    @Test
    void shouldRedactTheSameProtectedFailureBeforeAppending() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        FailureRedactor redactor = new FailureRedactor();
        EnvironmentEventPublisher publisher = publisher(
            journal,
            redactor,
            new JournalSlf4jEmitter(
                EnvironmentLogging.logs().frameworkLevel(LogLevel.OFF).build(),
                new JournalRenderer()
            )
        );
        RuntimeException failure = new RuntimeException(
            "provider=https://secret.example:9443 token=top-secret"
        );
        ConnectionId connectionId = ConnectionId.of("client[].api->server[].api");

        redactor.protectRoutePreparation(connectionId, failure);
        publisher.environmentStartupFailure(failure);

        FailureEvent.EnvironmentStartup stored = (FailureEvent.EnvironmentStartup)
            journal.snapshot().entries().getFirst().event();
        assertThat(stored.failure().message())
            .contains("Route preparation failed for connection 'client[].api->server[].api'");
        assertThat(stored.failure().message().orElseThrow())
            .doesNotContain("secret.example", "9443", "top-secret");
        assertThat(stored.failure().getClass().getDeclaredFields())
            .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType()));
    }

    @Test
    void shouldUseIdentityRatherThanThrowableEqualityForProtection() {
        FailureRedactor redactor = new FailureRedactor();
        EqualFailure protectedFailure = new EqualFailure("sensitive endpoint");
        EqualFailure equalButDistinct = new EqualFailure("sensitive endpoint");

        redactor.protectRouteCleanup(
            ConnectionId.of("client[].api->server[].api"),
            protectedFailure
        );

        assertThat(redactor.details(protectedFailure).message())
            .hasValueSatisfying(message -> assertThat(message)
                .contains("Route cleanup failed"));
        assertThat(redactor.details(equalButDistinct).message())
            .hasValueSatisfying(message -> assertThat(message)
                .contains("sensitive endpoint"));
    }

    private static EnvironmentEventPublisher publisher(
        ScenarioJournal journal,
        FailureRedactor redactor,
        JournalSlf4jEmitter emitter
    ) {
        return new EnvironmentEventPublisher(journal, redactor, emitter);
    }

    private static final class EqualFailure extends RuntimeException {
        private EqualFailure(String message) {
            super(message);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualFailure failure
                && getMessage().equals(failure.getMessage());
        }

        @Override
        public int hashCode() {
            return getMessage().hashCode();
        }
    }
}
