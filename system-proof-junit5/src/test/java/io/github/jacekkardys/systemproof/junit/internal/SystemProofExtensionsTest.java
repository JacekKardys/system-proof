package io.github.jacekkardys.systemproof.junit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.testkit.engine.EngineTestKit;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DiagnosticSource;
import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;

class SystemProofExtensionsTest {
    private static final String ARTIFACTS_DIRECTORY_PROPERTY = "system.proof.artifacts";
    private static final String CONTAINER_STDOUT_CANARY = "container-stdout-canary";
    private static final String CONTAINER_STDERR_CANARY = "container-stderr-canary";

    @Test
    void shouldCreateStartInjectAndCloseTheExactEnvironmentForEveryTest() {
        Recording.reset();

        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(SuccessfulScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(2).succeeded(2));
        assertThat(execution.testEvents().started().list())
            .extracting(event -> event.getTestDescriptor().getDisplayName())
            .containsExactlyInAnyOrder("first", "second");
        assertThat(Recording.definitions).hasValue(2);
        assertThat(Recording.starts).hasValue(2);
        assertThat(Recording.closes).hasValue(2);
        assertThat(Recording.diagnosticsCaptures).hasValue(0);
    }

    @Test
    void shouldCaptureFailureDiagnosticsBeforeClosingTheEnvironment(@TempDir Path artifacts)
        throws IOException {
        Recording.reset();
        String property = ARTIFACTS_DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, artifacts.toString());

        try {
            val execution = EngineTestKit.engine("junit-jupiter")
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
        String property = ARTIFACTS_DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, artifacts.toString());

        try {
            val execution = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(CleanupFailingScenario.class))
                .execute();

            execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
            assertThat(Files.readString(
                artifacts.resolve("CleanupFailingScenario-passes")
                    .resolve("environment.log")
            )).contains(
                "[STATE] component=cleanup type=cleanup state=FAILED",
                "[COMPONENT] [cleanup] Component cleanup failed",
                "IllegalStateException",
                "[FRAMEWORK] [environment] Environment failed",
                "[FRAMEWORK] [environment] Environment stopped"
            )
            .doesNotContain("cleanup exploded");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void shouldCloseTheEnvironmentWhenDiagnosticsCaptureFails() {
        Recording.reset();

        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(DiagnosticsFailingScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
        assertThat(Recording.diagnosticsFailureCloses).hasValue(1);
    }

    @Test
    void shouldNeverCaptureSensitiveSourcesInFailureArtifacts(@TempDir Path artifacts)
        throws IOException {
        String previousArtifacts = System.getProperty(ARTIFACTS_DIRECTORY_PROPERTY);
        System.setProperty(ARTIFACTS_DIRECTORY_PROPERTY, artifacts.toString());
        Recording.sensitiveCaptures.set(0);

        try {
            val execution = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(SensitiveDiagnosticsScenario.class))
                .execute();

            execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
            Path scenario = artifacts.resolve("SensitiveDiagnosticsScenario-fails");
            assertThat(Files.readString(scenario.resolve("environment.log")))
                .contains("sanitized container diagnostics")
                .doesNotContain(CONTAINER_STDOUT_CANARY, CONTAINER_STDERR_CANARY);
            assertThat(scenario.resolve("SENSITIVE-NOT-SECRET-SAFE")).doesNotExist();
            assertThat(Recording.sensitiveCaptures).hasValue(0);
            assertReportEntriesContainNoCanaries(execution);
        } finally {
            restoreProperty(ARTIFACTS_DIRECTORY_PROPERTY, previousArtifacts);
        }
    }

    @Test
    void shouldInjectTheSameEnvironmentIntoPerTestLifecycleMethods() {
        Recording.reset();

        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(LifecycleInjectionScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).succeeded(1));
        assertThat(Recording.beforeEachEnvironment.get()).isSameAs(Recording.current.get());
        assertThat(Recording.testEnvironment.get()).isSameAs(Recording.current.get());
        assertThat(Recording.afterEachEnvironment.get()).isSameAs(Recording.current.get());
        assertThat(Recording.closes).hasValue(1);
    }

    @Test
    void shouldPublishOptionalScenarioMetadata() {
        Recording.reset();

        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(MetadataScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).succeeded(1));
        assertThat(execution.testEvents().started().list())
            .extracting(event -> event.getTestDescriptor().getDisplayName())
            .contains("SMS ingestion");
        val reportEvents = execution.allEvents().reportingEntryPublished().list();
        assertThat(reportEvents).hasSize(1);
        val entries = reportEvents.getFirst()
            .getRequiredPayload(ReportEntry.class)
            .getKeyValuePairs();
        assertThat(entries)
            .hasSize(2)
            .containsEntry("system-proof.title", "SMS ingestion")
            .containsEntry("system-proof.description", "Persists one inbound SMS");
    }

    @Test
    void shouldRejectAMismatchedLifecycleEnvironmentBeforeInvokingTheMethod() {
        Recording.reset();

        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(MismatchedLifecycleScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
        val failure = execution.testEvents().failed().list().getFirst()
            .getRequiredPayload(TestExecutionResult.class)
            .getThrowable()
            .orElseThrow();
        assertThat(failure)
            .hasMessageContaining("MismatchedLifecycleScenario#setUp")
            .hasMessageContaining("exact type " + RecordingEnvironment.class.getName())
            .hasMessageContaining(DiagnosticsFailingEnvironment.class.getName());
        assertThat(Recording.mismatchedLifecycleInvocations).hasValue(0);
        assertThat(Recording.closes).hasValue(1);
    }

    @Test
    void shouldInjectARuntimeSubtypeThroughTheDeclaredEnvironmentContract() {
        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(BaseEnvironmentScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).succeeded(1));
    }

    @Test
    void shouldNotExpandTheDeclaredContractToTheRuntimeSubtype() {
        val execution = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(DerivedEnvironmentParameterScenario.class))
            .execute();

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).failed(1));
        val failure = execution.testEvents().failed().list().getFirst()
            .getRequiredPayload(TestExecutionResult.class)
            .getThrowable()
            .orElseThrow();
        assertThat(failure)
            .hasMessageContaining("exact type " + BaseEnvironment.class.getName())
            .hasMessageContaining(DerivedEnvironment.class.getName());
    }

    static class SuccessfulScenario {
        @SystemProof(RecordingEnvironment.class)
        void first(RecordingEnvironment environment) {
            assertThat(environment).isSameAs(Recording.current.get());
            assertThat(environment.isRunning()).isTrue();
        }

        @SystemProof(RecordingEnvironment.class)
        void second(RecordingEnvironment environment) {
            assertThat(environment).isSameAs(Recording.current.get());
            assertThat(environment.isRunning()).isTrue();
        }
    }

    static class FailingScenario {
        @SystemProof(RecordingEnvironment.class)
        void fails(RecordingEnvironment environment) {
            assertThat(environment).isNotSameAs(Recording.current.get());
        }
    }

    static class CleanupFailingScenario {
        @SystemProof(CleanupFailingEnvironment.class)
        void passes(CleanupFailingEnvironment environment) {
            assertThat(environment.isRunning()).isTrue();
        }
    }

    static class DiagnosticsFailingScenario {
        @SystemProof(DiagnosticsFailingEnvironment.class)
        void fails(DiagnosticsFailingEnvironment environment) {
            assertThat(environment.isRunning()).isTrue();
            throw new IllegalStateException("test exploded");
        }
    }

    static class SensitiveDiagnosticsScenario {
        @SystemProof(SensitiveDiagnosticsEnvironment.class)
        void fails(SensitiveDiagnosticsEnvironment environment) {
            assertThat(environment.isRunning()).isTrue();
            throw new IllegalStateException("scenario failure");
        }
    }

    static class LifecycleInjectionScenario {
        @BeforeEach
        void setUp(RecordingEnvironment environment) {
            assertThat(environment.isRunning()).isTrue();
            Recording.beforeEachEnvironment.set(environment);
        }

        @SystemProof(RecordingEnvironment.class)
        void exercisesBehavior(RecordingEnvironment environment) {
            assertThat(environment).isSameAs(Recording.beforeEachEnvironment.get());
            Recording.testEnvironment.set(environment);
        }

        @AfterEach
        void tearDown(RecordingEnvironment environment) {
            assertThat(environment).isSameAs(Recording.testEnvironment.get());
            assertThat(environment.isRunning()).isTrue();
            Recording.afterEachEnvironment.set(environment);
        }
    }

    static class MetadataScenario {
        @SystemProof(
            value = RecordingEnvironment.class,
            title = " SMS ingestion ",
            description = " Persists one inbound SMS "
        )
        void passes() {}
    }

    static class MismatchedLifecycleScenario {
        @BeforeEach
        void setUp(DiagnosticsFailingEnvironment environment) {
            Recording.mismatchedLifecycleInvocations.incrementAndGet();
        }

        @SystemProof(RecordingEnvironment.class)
        void passes() {}
    }

    static class BaseEnvironmentScenario {
        @SystemProof(BaseEnvironment.class)
        void acceptsDeclaredType(BaseEnvironment environment) {
            assertThat(environment).isInstanceOf(DerivedEnvironment.class);
            assertThat(environment.isRunning()).isTrue();
        }
    }

    static class DerivedEnvironmentParameterScenario {
        @SystemProof(BaseEnvironment.class)
        void rejectsRuntimeSubtype(DerivedEnvironment environment) {}
    }

    private static final class RecordingEnvironment extends Environment {
        private RecordingEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static RecordingEnvironment define() {
            return Recording.create();
        }
    }

    private static final class CleanupFailingEnvironment extends Environment {
        private CleanupFailingEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static CleanupFailingEnvironment define() {
            return new EnvironmentBuilder()
                .components(new CleanupFailingComponent())
                .build(CleanupFailingEnvironment::new);
        }
    }

    private static final class DiagnosticsFailingEnvironment extends Environment {
        private DiagnosticsFailingEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static DiagnosticsFailingEnvironment define() {
            return new EnvironmentBuilder()
                .components(new DiagnosticsFailingComponent())
                .build(DiagnosticsFailingEnvironment::new);
        }
    }

    private static final class SensitiveDiagnosticsEnvironment extends Environment {
        private SensitiveDiagnosticsEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging
        ) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static SensitiveDiagnosticsEnvironment define() {
            return new EnvironmentBuilder()
                .components(new SensitiveDiagnosticsComponent())
                .build(SensitiveDiagnosticsEnvironment::new);
        }
    }

    private static class BaseEnvironment extends Environment {
        private BaseEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static BaseEnvironment define() {
            return new EnvironmentBuilder()
                .components(new RecordingComponent())
                .build(DerivedEnvironment::new);
        }
    }

    private static final class DerivedEnvironment extends BaseEnvironment {
        private DerivedEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
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
                        .diagnostics(DiagnosticSource.redacted(
                            "runtime-state",
                            () -> {
                                Recording.diagnosticsCaptures.incrementAndGet();
                                return Recording.closes.get() == 0
                                    ? "runtime-before-close"
                                    : "runtime-after-close";
                            },
                            input -> input
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

    private static final class DiagnosticsFailingComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("diagnostics-failure");

        private DiagnosticsFailingComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime
                    .<Void>runtime(Recording.diagnosticsFailureCloses::incrementAndGet)
                    .diagnostics(DiagnosticSource.redacted(
                        "broken-diagnostics",
                        () -> {
                            throw new AssertionError("diagnostics exploded");
                        },
                        input -> input
                    ))
                    .build()
            );
        }
    }

    private static final class SensitiveDiagnosticsComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("sensitive-diagnostics");

        private SensitiveDiagnosticsComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .diagnostics(DiagnosticSource.redacted(
                        "container-safe-output",
                        () -> CONTAINER_STDOUT_CANARY + System.lineSeparator()
                            + CONTAINER_STDERR_CANARY,
                        ignored -> "sanitized container diagnostics"
                    ))
                    .diagnostics(DiagnosticSource.sensitive(
                        "container-raw-output",
                        () -> {
                            Recording.sensitiveCaptures.incrementAndGet();
                            return "stdout=" + CONTAINER_STDOUT_CANARY
                                + System.lineSeparator()
                                + "stderr=" + CONTAINER_STDERR_CANARY;
                        }
                    ))
                    .build()
            );
        }
    }

    private static final class Recording {
        private static final AtomicInteger definitions = new AtomicInteger();
        private static final AtomicInteger starts = new AtomicInteger();
        private static final AtomicInteger closes = new AtomicInteger();
        private static final AtomicInteger diagnosticsCaptures = new AtomicInteger();
        private static final AtomicInteger diagnosticsFailureCloses = new AtomicInteger();
        private static final AtomicInteger sensitiveCaptures = new AtomicInteger();
        private static final AtomicInteger mismatchedLifecycleInvocations = new AtomicInteger();
        private static final AtomicReference<RecordingEnvironment> current = new AtomicReference<>();
        private static final AtomicReference<RecordingEnvironment> beforeEachEnvironment =
            new AtomicReference<>();
        private static final AtomicReference<RecordingEnvironment> testEnvironment =
            new AtomicReference<>();
        private static final AtomicReference<RecordingEnvironment> afterEachEnvironment =
            new AtomicReference<>();

        private static RecordingEnvironment create() {
            definitions.incrementAndGet();
            RecordingEnvironment environment = new EnvironmentBuilder()
                .components(new RecordingComponent())
                .build(RecordingEnvironment::new);
            current.set(environment);
            return environment;
        }

        private static void reset() {
            definitions.set(0);
            starts.set(0);
            closes.set(0);
            diagnosticsCaptures.set(0);
            diagnosticsFailureCloses.set(0);
            sensitiveCaptures.set(0);
            mismatchedLifecycleInvocations.set(0);
            current.set(null);
            beforeEachEnvironment.set(null);
            testEnvironment.set(null);
            afterEachEnvironment.set(null);
        }
    }

    private static void assertReportEntriesContainNoCanaries(
        org.junit.platform.testkit.engine.EngineExecutionResults execution
    ) {
        assertThat(execution.allEvents().reportingEntryPublished().list())
            .allSatisfy(event -> assertThat(event.getRequiredPayload(ReportEntry.class)
                .getKeyValuePairs().toString())
                .doesNotContain(CONTAINER_STDOUT_CANARY, CONTAINER_STDERR_CANARY));
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
