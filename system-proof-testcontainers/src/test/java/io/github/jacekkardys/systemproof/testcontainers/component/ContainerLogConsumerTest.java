package io.github.jacekkardys.systemproof.testcontainers.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.output.OutputFrame;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.driver.JournalContributions;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;
import io.github.jacekkardys.systemproof.topology.PortRef;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

class ContainerLogConsumerTest {
    private static final String SECRET = "container-output-canary-secret";
    private static final String STDOUT_SECRET = "container-stdout-canary-secret";
    private static final String STDERR_SECRET = "container-stderr-canary-secret";
    private static final ComponentType TYPE = ComponentType.of("container");
    private static final Component COMPONENT = new TestComponent();

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
    void shouldOmitRawOutputWhenNoSanitizerOrSensitiveOptInExists() {
        RecordingContext context = new RecordingContext();
        ContainerLogConsumer consumer = new ContainerLogConsumer(
            context,
            COMPONENT,
            null
        );

        consumer.accept(frame(OutputFrame.OutputType.STDOUT, "INFO " + STDOUT_SECRET));
        consumer.accept(frame(OutputFrame.OutputType.STDERR, "ERROR " + STDERR_SECRET));

        assertThat(context.message).isNull();
    }

    @Test
    void shouldJournalOnlyExplicitlySanitizedBoundedOutput() {
        RecordingContext context = new RecordingContext();
        ContainerLogConsumer consumer = new ContainerLogConsumer(
            context,
            COMPONENT,
            output -> output.replace(SECRET, "[redacted]")
        );

        consumer.accept(frame(OutputFrame.OutputType.STDOUT, "INFO value=" + SECRET));

        assertThat(context.component).isSameAs(COMPONENT);
        assertThat(context.level).isEqualTo(LogLevel.INFO);
        assertThat(context.message.content())
            .isEqualTo("INFO value=[redacted]")
            .doesNotContain(SECRET);
        assertThat(context.message.toString()).doesNotContain(SECRET);
    }

    @Test
    void shouldFailSafeWhenSanitizerThrowsOrReturnsNullOrBlank() {
        for (RedactedDiagnosticText.Sanitizer sanitizer : List.<RedactedDiagnosticText.Sanitizer>of(
            input -> { throw new IllegalStateException(SECRET); },
            input -> null,
            input -> "   "
        )) {
            RecordingContext context = new RecordingContext();
            ContainerLogConsumer consumer = new ContainerLogConsumer(
                context,
                COMPONENT,
                sanitizer
            );

            consumer.accept(frame(OutputFrame.OutputType.STDOUT, SECRET));

            assertThat(context.message.content())
                .contains("DIAGNOSTIC OMITTED")
                .doesNotContain(SECRET);
        }
    }

    @Test
    void shouldBoundHostileInputBeforeInvokingTheSanitizerAndBoundItsOutput() {
        AtomicInteger consideredInput = new AtomicInteger();
        RecordingContext context = new RecordingContext();
        ContainerLogConsumer consumer = new ContainerLogConsumer(
            context,
            COMPONENT,
            input -> {
                consideredInput.set(input.length());
                return ("safe-line" + System.lineSeparator()).repeat(1_000);
            }
        );

        consumer.accept(frame(OutputFrame.OutputType.STDOUT, "x".repeat(1_000_000)));

        assertThat(consideredInput).hasValueLessThanOrEqualTo(16 * 1024);
        assertThat(context.message.content()).hasSizeLessThanOrEqualTo(4 * 1024);
        assertThat(context.message.content().lines()).hasSizeLessThanOrEqualTo(64);
        assertThat(context.message.truncated()).isTrue();
    }

    private static OutputFrame frame(OutputFrame.OutputType type, String content) {
        return new OutputFrame(type, content.getBytes(StandardCharsets.UTF_8));
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class TestComponent implements Component {
        @Override
        public ComponentId id() {
            return ComponentId.component(TYPE);
        }

        @Override
        public ComponentType type() {
            return TYPE;
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

    private static final class RecordingContext implements DriverContext {
        private Component component;
        private LogLevel level;
        private RedactedDiagnosticText message;

        @Override
        public <T> T resolve(RequiredPort<T> required) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R extends AutoCloseable> R sharedResource(
            DriverResourceKey<R> key,
            Supplier<? extends R> factory
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void log(
            Component component,
            LogLevel level,
            RedactedDiagnosticText message
        ) {
            this.component = component;
            this.level = level;
            this.message = message;
        }

        @Override
        public JournalContributions journalContributions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String componentEvents(Component component) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ComponentState state(Component component) {
            throw new UnsupportedOperationException();
        }
    }
}
