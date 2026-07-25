package io.github.jacekkardys.systemproof.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.Contract.contract;
import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;
import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.InteractionMetadata;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.Contract;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.model.InteractionSpec;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

class JournalContributionBoundaryTest {
    private static final ComponentType TYPE = ComponentType.of("observer");
    private static final ComponentType PROVIDER = ComponentType.of("provider");
    private static final Contract<String> API = contract("api", String.class);

    @Test
    void shouldCaptureExternalTypedEvidenceThroughAComponentScopedDriverCapability() {
        MutableInteractionEvidence source = new MutableInteractionEvidence(
            "sensitive-binary".getBytes(StandardCharsets.UTF_8),
            new ArrayList<>(List.of("original-attribute"))
        );
        AtomicReference<String> renderedByDriver = new AtomicReference<>();
        ProviderComponent provider = new ProviderComponent();
        ConnectedObserver component = new ConnectedObserver((current, context) -> {
            JournalContributions contributions = context.journalContributions();
            contributions.observeInteraction(
                new InteractionMetadata(
                    Optional.of(ConnectionId.between(
                        ((ConnectedObserver) current).api,
                        provider.api
                    )),
                    Optional.of(InteractionMetadata.Direction.OUTBOUND)
                ),
                MutableInteractionEvidence.codec(),
                source
            );
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
        Environment environment = Environment.environment()
            .components(component, provider)
            .connect(component.api, provider.api)
            .build()
            .start();

        var captured = environment.journalSnapshot();
        source.payload()[0] = 'X';
        source.attributes().set(0, "mutated-attribute");

        InteractionObservationEvent interaction = captured.entries().stream()
            .map(entry -> entry.event())
            .filter(InteractionObservationEvent.class::isInstance)
            .map(InteractionObservationEvent.class::cast)
            .findFirst()
            .orElseThrow();
        MutableInteractionEvidence decoded =
            interaction.evidence().decode(MutableInteractionEvidence.codec());

        assertThat(interaction.observingComponentId()).isEqualTo(component.id());
        assertThat(interaction.metadata().connectionId())
            .contains(environment.connections().getFirst().id());
        assertThat(decoded.payload())
            .containsExactly("sensitive-binary".getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.attributes()).containsExactly("original-attribute");

        decoded.payload()[0] = 'Y';
        decoded.attributes().set(0, "decoded-mutation");
        MutableInteractionEvidence decodedAgain =
            interaction.evidence().decode(MutableInteractionEvidence.codec());
        assertThat(decodedAgain.payload())
            .containsExactly("sensitive-binary".getBytes(StandardCharsets.UTF_8));
        assertThat(decodedAgain.attributes()).containsExactly("original-attribute");

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
                "[INTERACTION] [observer-connected]",
                "[CHECKPOINT] [observer-connected] [request-recorded]",
                "[DISRUPTION] [observer-connected] [latency-window]"
            )
            .contains("schema=test.external:interaction", "encodedBytes=")
            .doesNotContain("sensitive-binary", "original-attribute");

        environment.close();
    }

    @Test
    void shouldExposeOnlySupportedSubjectScopedContributionOperations() {
        assertThat(ScenarioEvent.class.isAssignableFrom(MutableInteractionEvidence.class))
            .isFalse();
        assertThat(DriverContext.class.getMethods())
            .extracting(Method::getReturnType)
            .doesNotContain(ScenarioJournal.class);
        assertThat(JournalContributions.class.getMethods())
            .extracting(Method::getName)
            .containsExactlyInAnyOrder(
                "observeInteraction",
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
                        ScenarioEvent.class,
                        ScenarioJournal.class
                    );
            });
    }

    @Test
    void shouldRejectDriverDiagnosticsForAnotherComponentIdentity() {
        AtomicReference<TestComponent> other = new AtomicReference<>();
        TestComponent intruder = new TestComponent("intruder", (component, context) -> {
            context.log(other.get(), LogLevel.INFO, "forged diagnostic");
            return ComponentRuntime.<Void>runtime().build();
        });
        TestComponent victim = new TestComponent(
            "victim",
            (component, context) -> ComponentRuntime.<Void>runtime().build()
        );
        other.set(victim);
        Environment environment = Environment.environment()
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

    @Test
    void shouldRejectInteractionMetadataForAConnectionOutsideTheEnvironment() {
        TestComponent component = new TestComponent((current, context) -> {
            context.journalContributions().observeInteraction(
                InteractionMetadata.onConnection(
                    ConnectionId.of("missing[].required->missing[].provided"),
                    InteractionMetadata.Direction.OUTBOUND
                ),
                MutableInteractionEvidence.codec(),
                new MutableInteractionEvidence(new byte[] {1}, new ArrayList<>())
            );
            return ComponentRuntime.<Void>runtime().build();
        });
        Environment environment = Environment.environment()
            .components(component)
            .build();

        assertThatThrownBy(environment::start)
            .isInstanceOf(EnvironmentStartException.class)
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage(
                "Interaction metadata references connection "
                    + "'missing[].required->missing[].provided' outside the environment"
            );
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

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
        }
    }

    private enum Http implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "http";
        }

        @Override
        public String scheme() {
            return "http";
        }
    }

    private static final class ConnectedObserver
        extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<String> api;

        private ConnectedObserver(ComponentDriver<EmptyConfig, Void> driver) {
            super(
                ComponentId.component(TYPE, "connected"),
                new EmptyConfig(),
                Void.class,
                driver
            );
            api = requiresAtStartup(
                "api",
                API,
                Invocation.INSTANCE,
                Http.INSTANCE
            );
        }

    }

    private static final class ProviderComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private final ProvidedPort<String> api;

        private ProviderComponent() {
            super(
                ComponentId.component(PROVIDER),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .provides(
                        ((ProviderComponent) component).api,
                        binding("provider.internal", "provider.external")
                    )
                    .build()
            );
            api = provides("api", API, Invocation.INSTANCE, Http.INSTANCE);
        }

    }

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
