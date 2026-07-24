package pl.gov.il.test.harness.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;
import pl.gov.il.test.harness.driver.ComponentRuntime;
import pl.gov.il.test.harness.driver.DiagnosticSource;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.RuntimeConfig;
import pl.gov.il.test.harness.model.ComponentId;
import pl.gov.il.test.harness.model.ComponentType;
import pl.gov.il.test.harness.model.Environment;

class EnvironmentTestExtensionTest {
    @Test
    void shouldCreateStartInjectAndCloseTheExactEnvironmentForEveryTest() {
        Recording.reset();

        var execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(SuccessfulScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(2).succeeded(2));
        assertThat(Recording.definitions).hasValue(2);
        assertThat(Recording.starts).hasValue(2);
        assertThat(Recording.closes).hasValue(2);
    }

    @Test
    void shouldCaptureFailureDiagnosticsBeforeClosingTheEnvironment(@TempDir Path artifacts)
        throws IOException {
        Recording.reset();
        String property = EnvironmentDiagnosticsWriter.ARTIFACTS_DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, artifacts.toString());

        try {
            var execution = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(FailingScenario.class))
                .execute();

            execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
            assertThat(Recording.starts).hasValue(1);
            assertThat(Recording.closes).hasValue(1);
            assertThat(Files.readString(
                artifacts.resolve("FailingScenario-fails").resolve("environment.log")
            )).contains("runtime-before-close").doesNotContain("runtime-after-close");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @EnvironmentTest(environment = RecordingEnvironment.class)
    static class SuccessfulScenario {
        @Test
        void first(RecordingEnvironment environment) {
            assertThat(environment).isSameAs(Recording.current.get());
            assertThat(environment.isRunning()).isTrue();
        }

        @Test
        void second(RecordingEnvironment environment) {
            assertThat(environment).isSameAs(Recording.current.get());
            assertThat(environment.isRunning()).isTrue();
        }
    }

    @EnvironmentTest(environment = RecordingEnvironment.class)
    static class FailingScenario {
        @Test
        void fails(RecordingEnvironment environment) {
            assertThat(environment).isNotSameAs(Recording.current.get());
        }
    }

    private static final class RecordingEnvironment extends Environment {
        private RecordingEnvironment() {
            super(Environment.environment().components(new RecordingComponent()));
        }

        @EnvironmentDefinition
        private static RecordingEnvironment define() {
            return Recording.create();
        }
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class RecordingComponent extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("recording");

        private RecordingComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> {
                    Recording.starts.incrementAndGet();
                    return ComponentRuntime.<Void>runtime(Recording.closes::incrementAndGet)
                        .diagnostics(new DiagnosticSource(
                            "runtime-state",
                            () -> Recording.closes.get() == 0
                                ? "runtime-before-close"
                                : "runtime-after-close"
                        ))
                        .build();
                }
            );
        }

        @Override
        protected ComponentType componentType() {
            return TYPE;
        }
    }

    private static final class Recording {
        private static final AtomicInteger definitions = new AtomicInteger();
        private static final AtomicInteger starts = new AtomicInteger();
        private static final AtomicInteger closes = new AtomicInteger();
        private static final AtomicReference<RecordingEnvironment> current = new AtomicReference<>();

        private static RecordingEnvironment create() {
            definitions.incrementAndGet();
            RecordingEnvironment environment = new RecordingEnvironment();
            current.set(environment);
            return environment;
        }

        private static void reset() {
            definitions.set(0);
            starts.set(0);
            closes.set(0);
            current.set(null);
        }
    }
}
