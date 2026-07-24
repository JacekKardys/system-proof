package pl.gov.il.test.harness.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pl.gov.il.test.harness.api.EnvironmentLogging;
import pl.gov.il.test.harness.model.LogLevel;

class EnvironmentEventLogTest {
    @Test
    void shouldUseOneMonotonicRelativeTimeline() {
        AtomicLong clock = new AtomicLong();
        EnvironmentEventLog eventLog = new EnvironmentEventLog(EnvironmentLogging.defaults(), clock::get);

        clock.set(TimeUnit.MILLISECONDS.toNanos(250));
        eventLog.framework(LogLevel.INFO, "Starting environment");
        clock.set(TimeUnit.MILLISECONDS.toNanos(1_195));
        eventLog.framework(LogLevel.INFO, "Environment ready");

        assertThat(eventLog.snapshot().content()).isEqualTo(
            "T+00:00:00.250 [FRAMEWORK] [environment] Starting environment" + System.lineSeparator()
                + "T+00:00:01.195 [FRAMEWORK] [environment] Environment ready"
        );
    }

    @Test
    void shouldRetainEventsBelowTheEmissionThresholdForFailureDiagnostics() {
        EnvironmentEventLog eventLog = new EnvironmentEventLog(
            EnvironmentLogging.logs().frameworkLevel(LogLevel.WARN).build(),
            () -> 0
        );

        eventLog.framework(LogLevel.DEBUG, "Configuration details");
        eventLog.framework(LogLevel.INFO, "Startup progress");

        assertThat(eventLog.snapshot().content())
            .contains("Configuration details")
            .contains("Startup progress");
    }
}
