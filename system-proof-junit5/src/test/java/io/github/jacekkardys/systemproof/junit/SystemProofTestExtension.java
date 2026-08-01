package io.github.jacekkardys.systemproof.junit;

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
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DiagnosticSource;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Environment;

class SystemProofTestExtension {
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
            ))
                .contains(
                    "runtime-before-close",
                    "[FRAMEWORK] [environment] Starting environment",
                    "[COMPONENT] [recording] Component ready"
                )
                .doesNotContain("runtime-after-close");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void shouldCaptureStructuredCleanupFailureDiagnosticsAfterClose(@TempDir Path artifacts)
        throws IOException {
        String property = EnvironmentDiagnosticsWriter.ARTIFACTS_DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, artifacts.toString());

        try {
            var execution = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(CleanupFailingScenario.class))
                .execute();

            execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
            assertThat(Files.readString(
                artifacts.resolve("CleanupFailingScenario-passes")
                    .resolve("environment.log")
            )).contains(
                "[STATE] component=cleanup type=cleanup state=FAILED",
                "[COMPONENT] [cleanup] Component cleanup failed",
                "cleanup exploded",
                "[FRAMEWORK] [environment] Environment failed",
                "[FRAMEWORK] [environment] Environment stopped"
            );
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @SystemProof(environment = RecordingEnvironment.class)
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

    @SystemProof(environment = RecordingEnvironment.class)
    static class FailingScenario {
        @Test
        void fails(RecordingEnvironment environment) {
            assertThat(environment).isNotSameAs(Recording.current.get());
        }
    }

    @SystemProof(environment = CleanupFailingEnvironment.class)
    static class CleanupFailingScenario {
        @Test
        void passes(CleanupFailingEnvironment environment) {
            assertThat(environment.isRunning()).isTrue();
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

    private static final class CleanupFailingEnvironment extends Environment {
        private CleanupFailingEnvironment() {
            super(Environment.environment().components(new CleanupFailingComponent()));
        }

        @EnvironmentDefinition
        private static CleanupFailingEnvironment define() {
            return new CleanupFailingEnvironment();
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

    }

    private static final class CleanupFailingComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("cleanup");

        private CleanupFailingComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime(() -> {
                    throw new IllegalStateException("cleanup exploded");
                }).build()
            );
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
