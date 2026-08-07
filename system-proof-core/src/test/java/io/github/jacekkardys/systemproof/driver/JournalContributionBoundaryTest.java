package io.github.jacekkardys.systemproof.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.environment.EnvironmentStartException;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;

class JournalContributionBoundaryTest {
    private static final ComponentType TYPE = ComponentType.of("observer");

    @Test
    void shouldCaptureOnlyComponentOwnedContributionsThroughTheDriverCapability() {
        AtomicReference<String> renderedByDriver = new AtomicReference<>();
        TestComponent component = new TestComponent((current, context) -> {
            JournalContributions contributions = context.journalContributions();
            contributions.recordCheckpoint(
                new CheckpointId("request-recorded"),
                CheckpointEvent.Kind.CHECKPOINT,
                CheckpointEvent.Stage.OBSERVED
            );
            contributions.recordDisruption(
                new DisruptionId("latency-window"),
                DisruptionLifecycleEvent.Stage.DECLARED
            );
            renderedByDriver.set(context.componentEvents(current));
            return ComponentRuntime.<Void>runtime().build();
        });
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build()
            .start();

        var captured = environment.journalSnapshot();

        assertThat(events(captured, InteractionObservationEvent.class)).isEmpty();
        assertThat(events(captured, CheckpointEvent.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.observingComponentId()).isEqualTo(component.id());
                assertThat(event.checkpointId()).isEqualTo(new CheckpointId("request-recorded"));
                assertThat(event.stage()).isEqualTo(CheckpointEvent.Stage.OBSERVED);
            });
        assertThat(events(captured, DisruptionLifecycleEvent.class))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.observingComponentId()).isEqualTo(component.id());
                assertThat(event.disruptionId()).isEqualTo(new DisruptionId("latency-window"));
                assertThat(event.stage()).isEqualTo(DisruptionLifecycleEvent.Stage.DECLARED);
            });
        assertThat(renderedByDriver.get())
            .containsSubsequence(
                "[CHECKPOINT] [observer] [request-recorded]",
                "[DISRUPTION] [observer] [latency-window]"
            );

        environment.close();
    }

    @Test
    void shouldExposeOnlySupportedComponentScopedContributionOperations() {
        assertThat(ScenarioEvent.class.isAssignableFrom(MutableInteractionEvidence.class))
            .isFalse();
        assertThat(DriverContext.class.getMethods())
            .extracting(Method::getReturnType)
            .doesNotContain(ScenarioEvent.class);
        assertThat(JournalContributions.class.getMethods())
            .extracting(Method::getName)
            .containsExactlyInAnyOrder(
                "recordCheckpoint",
                "recordDisruption"
            );
        assertThat(JournalContributions.class.getMethods())
            .allSatisfy(method -> {
                assertThat(method.getReturnType()).isEqualTo(void.class);
                assertThat(Arrays.asList(method.getParameterTypes()))
                    .doesNotContain(
                        Component.class,
                        ComponentId.class,
                        ScenarioEvent.class
                    );
            });
    }

    @Test
    void shouldRejectDriverDiagnosticsForAnotherComponentIdentity() {
        AtomicReference<TestComponent> other = new AtomicReference<>();
        TestComponent intruder = new TestComponent("intruder", (component, context) -> {
            context.log(
                other.get(),
                LogLevel.INFO,
                RedactedDiagnosticText.redact("forged diagnostic", input -> input)
            );
            return ComponentRuntime.<Void>runtime().build();
        });
        TestComponent victim = new TestComponent(
            "victim",
            (component, context) -> ComponentRuntime.<Void>runtime().build()
        );
        other.set(victim);
        Environment environment = new EnvironmentBuilder()
            .components(intruder, victim)
            .build();

        assertThatThrownBy(environment::start)
            .isInstanceOf(EnvironmentStartException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage(
                "Driver for component 'observer-intruder' cannot write diagnostics "
                    + "for component 'observer-victim'"
            );
        assertThat(environment.diagnostics().content()).doesNotContain("forged diagnostic");
    }

    private static <T extends ScenarioEvent> List<T> events(
        io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot snapshot,
        Class<T> eventType
    ) {
        return snapshot.entries().stream()
            .map(entry -> entry.event())
            .filter(eventType::isInstance)
            .map(eventType::cast)
            .toList();
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class TestComponent extends AbstractComponent<EmptyConfig, Void> {
        private TestComponent(ComponentDriver<EmptyConfig, Void> driver) {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                driver
            );
        }

        private TestComponent(String qualifier, ComponentDriver<EmptyConfig, Void> driver) {
            super(
                ComponentId.component(TYPE, qualifier),
                new EmptyConfig(),
                Void.class,
                driver
            );
        }
    }
}
