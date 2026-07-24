package io.github.jacekkardys.systemproof.testcontainers.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.output.OutputFrame;
import io.github.jacekkardys.systemproof.model.LogLevel;

class ContainerLogConsumerTest {
    @Test
    void shouldDetectStructuredAndTextLogLevels() {
        assertThat(ContainerLogConsumer.detectLevel(
            OutputFrame.OutputType.STDOUT,
            "{\"log\":{\"level\":\"DEBUG\"},\"message\":\"request\"}"
        )).isEqualTo(LogLevel.DEBUG);
        assertThat(ContainerLogConsumer.detectLevel(
            OutputFrame.OutputType.STDERR,
            "[main] INFO service - started"
        )).isEqualTo(LogLevel.INFO);
        assertThat(ContainerLogConsumer.detectLevel(
            OutputFrame.OutputType.STDOUT,
            "WARNING: retrying"
        )).isEqualTo(LogLevel.WARN);
    }

    @Test
    void shouldFallBackToTheContainerStreamSeverity() {
        assertThat(ContainerLogConsumer.detectLevel(OutputFrame.OutputType.STDOUT, "ready"))
            .isEqualTo(LogLevel.INFO);
        assertThat(ContainerLogConsumer.detectLevel(OutputFrame.OutputType.STDERR, "unexpected failure"))
            .isEqualTo(LogLevel.ERROR);
    }
}
