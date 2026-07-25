package io.github.jacekkardys.systemproof.testcontainers.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.output.OutputFrame;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.PortRef;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

class ContainerLogConsumerTest {
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
    void shouldFallBackToTheContainerStreamSeverity() {
        assertThat(ContainerLogConsumer.detectLevel(OutputFrame.OutputType.STDOUT, "ready"))
            .isEqualTo(LogLevel.INFO);
        assertThat(ContainerLogConsumer.detectLevel(OutputFrame.OutputType.STDERR, "unexpected failure"))
            .isEqualTo(LogLevel.ERROR);
    }

    @Test
    void shouldForwardOneMultilineComponentDiagnosticWithoutLosingItsSubjectOrLevel() {
        RecordingContext context = new RecordingContext();
        ContainerLogConsumer consumer = new ContainerLogConsumer(context, COMPONENT);

        consumer.accept(new OutputFrame(
            OutputFrame.OutputType.STDOUT,
            ("INFO first line" + System.lineSeparator() + "second line"
                + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)
        ));

        assertThat(context.component).isSameAs(COMPONENT);
        assertThat(context.level).isEqualTo(LogLevel.INFO);
        assertThat(context.message).isEqualTo(
            "INFO first line" + System.lineSeparator() + "second line"
        );
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
        private String message;

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
        public void log(Component component, LogLevel level, String message) {
            this.component = component;
            this.level = level;
            this.message = message;
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
