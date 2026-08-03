package io.github.jacekkardys.systemproof.junit.internal;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

class SystemProofTemplateConflictTest {

    private static final String UNSUPPORTED_SUFFIX =
        "Combining @SystemProof with another @TestTemplate-based annotation is not supported.";

    @Test
    void shouldExecuteOrdinarySystemProofOnceWithBenignMethodAnnotations() {
        Recording.reset();

        val execution = execute(OrdinaryScenario.class);

        execution.testEvents().assertStatistics(statistics -> statistics.started(1).succeeded(1));
        assertThat(execution.testEvents().started().list())
            .extracting(event -> event.getTestDescriptor().getDisplayName())
            .containsExactly("ordinary title");
        assertThat(Recording.definitions).hasValue(1);
        assertThat(Recording.constructions).hasValue(1);
        assertThat(Recording.starts).hasValue(1);
        assertThat(Recording.beforeEachInvocations).hasValue(1);
        assertThat(Recording.testInvocations).hasValue(1);
        assertThat(Recording.afterEachInvocations).hasValue(1);
        assertThat(Recording.closes).hasValue(1);
    }

    @Test
    void shouldRejectParameterizedTestBeforeCreatingAnyInvocationOrEnvironment() {
        assertRejected(
            ParameterizedConflictScenario.class,
            "@SystemProof cannot be combined with @ParameterizedTest because both annotations "
                + "define test-template invocations. " + UNSUPPORTED_SUFFIX
        );
    }

    @Test
    void shouldRejectRepeatedTestBeforeCreatingAnyInvocationOrEnvironment() {
        assertRejected(
            RepeatedConflictScenario.class,
            "@SystemProof cannot be combined with @RepeatedTest because both annotations define "
                + "test-template invocations. " + UNSUPPORTED_SUFFIX
        );
    }

    @Test
    void shouldRejectCustomMetaAnnotatedTestTemplate() {
        assertRejected(
            CustomTemplateConflictScenario.class,
            "@SystemProof cannot be combined with @ExternalTestTemplate because both annotations "
                + "define test-template invocations. " + UNSUPPORTED_SUFFIX
        );
    }

    @Test
    void shouldRejectDirectTestTemplateAnnotation() {
        assertRejected(
            DirectTemplateConflictScenario.class,
            "@SystemProof cannot be combined with @TestTemplate because both annotations define "
                + "test-template invocations. " + UNSUPPORTED_SUFFIX
        );
    }

    @Test
    void shouldReportAllConflictsInDeterministicOrder() {
        assertRejected(
            MultipleTemplateConflictsScenario.class,
            "@SystemProof cannot be combined with @ParameterizedTest, @RepeatedTest because all "
                + "annotations define test-template invocations. " + UNSUPPORTED_SUFFIX
        );
    }

    private static ExtensionConfigurationException assertRejected(
        Class<?> scenario,
        String expectedMessage
    ) {
        Recording.reset();

        val execution = execute(scenario);

        assertThat(execution.testEvents().started().list()).isEmpty();
        assertThat(execution.allEvents().failed().list()).hasSize(1);
        assertThat(Recording.definitions).hasValue(0);
        assertThat(Recording.constructions).hasValue(0);
        assertThat(Recording.starts).hasValue(0);
        assertThat(Recording.beforeEachInvocations).hasValue(0);
        assertThat(Recording.testInvocations).hasValue(0);
        assertThat(Recording.afterEachInvocations).hasValue(0);
        assertThat(Recording.closes).hasValue(0);

        val observedFailure = execution.allEvents().failed().list().getFirst()
            .getRequiredPayload(TestExecutionResult.class)
            .getThrowable()
            .orElseThrow();
        val configurationFailure = findCause(
            observedFailure,
            ExtensionConfigurationException.class
        );
        assertThat(configurationFailure).hasMessage(expectedMessage);
        return configurationFailure;
    }

    private static EngineExecutionResults execute(Class<?> scenario) {
        return EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(scenario))
            .execute();
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        throw new AssertionError(
            "Expected cause " + expectedType.getName() + " in " + failure,
            failure
        );
    }

    @Retention(RUNTIME)
    @Target(METHOD)
    private @interface ScenarioMarker {}

    @Retention(RUNTIME)
    @Target(METHOD)
    @TestTemplate
    private @interface ExternalTestTemplate {}

    private abstract static class RecordingLifecycle {
        @BeforeEach
        void beforeEach() {
            Recording.beforeEachInvocations.incrementAndGet();
        }

        @AfterEach
        void afterEach() {
            Recording.afterEachInvocations.incrementAndGet();
        }
    }

    static class OrdinaryScenario extends RecordingLifecycle {
        @Tag("ordinary")
        @DisplayName("benign display name")
        @ScenarioMarker
        @SystemProof(value = RecordingEnvironment.class, title = "ordinary title")
        void passes(RecordingEnvironment environment) {
            assertThat(environment.isRunning()).isTrue();
            Recording.testInvocations.incrementAndGet();
        }
    }

    static class ParameterizedConflictScenario extends RecordingLifecycle {
        @SystemProof(RecordingEnvironment.class)
        @ParameterizedTest
        @ValueSource(strings = {"first", "second"})
        void rejected(String input, RecordingEnvironment environment) {
            Recording.testInvocations.incrementAndGet();
        }
    }

    static class RepeatedConflictScenario extends RecordingLifecycle {
        @SystemProof(RecordingEnvironment.class)
        @RepeatedTest(2)
        void rejected(RecordingEnvironment environment) {
            Recording.testInvocations.incrementAndGet();
        }
    }

    static class CustomTemplateConflictScenario extends RecordingLifecycle {
        @SystemProof(RecordingEnvironment.class)
        @ExternalTestTemplate
        void rejected(RecordingEnvironment environment) {
            Recording.testInvocations.incrementAndGet();
        }
    }

    static class DirectTemplateConflictScenario extends RecordingLifecycle {
        @SystemProof(RecordingEnvironment.class)
        @TestTemplate
        void rejected(RecordingEnvironment environment) {
            Recording.testInvocations.incrementAndGet();
        }
    }

    static class MultipleTemplateConflictsScenario extends RecordingLifecycle {
        @SystemProof(RecordingEnvironment.class)
        @RepeatedTest(2)
        @ParameterizedTest
        @ValueSource(strings = "only")
        void rejected(String input, RecordingEnvironment environment) {
            Recording.testInvocations.incrementAndGet();
        }
    }

    private static final class RecordingEnvironment extends Environment {
        private RecordingEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
            Recording.constructions.incrementAndGet();
        }

        @EnvironmentDefinition
        private static RecordingEnvironment define() {
            Recording.definitions.incrementAndGet();
            return new EnvironmentBuilder()
                .components(new RecordingComponent())
                .build(RecordingEnvironment::new);
        }
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class RecordingComponent extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("template-conflict-recording");

        private RecordingComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> {
                    Recording.starts.incrementAndGet();
                    return ComponentRuntime.<Void>runtime(Recording.closes::incrementAndGet)
                        .build();
                }
            );
        }
    }

    private static final class Recording {
        private static final AtomicInteger definitions = new AtomicInteger();
        private static final AtomicInteger constructions = new AtomicInteger();
        private static final AtomicInteger starts = new AtomicInteger();
        private static final AtomicInteger beforeEachInvocations = new AtomicInteger();
        private static final AtomicInteger testInvocations = new AtomicInteger();
        private static final AtomicInteger afterEachInvocations = new AtomicInteger();
        private static final AtomicInteger closes = new AtomicInteger();
        private static final List<AtomicInteger> COUNTERS = List.of(
            definitions,
            constructions,
            starts,
            beforeEachInvocations,
            testInvocations,
            afterEachInvocations,
            closes
        );

        private static void reset() {
            COUNTERS.forEach(counter -> counter.set(0));
        }
    }
}
