package io.github.jacekkardys.systemproof.testcontainers.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentStartException;

class TestcontainersDriverSecretSafetyTest {
    private static final String STARTUP_SECRET =
        "startup-token-canary-4f95b4e7\nsecond-secret-line";
    private static final String UNTERMINATED_SECRET =
        "unterminated-container-output-canary-9af84c3e";

    @Test
    void shouldRegisterNoContainerLogConsumersByDefault() {
        InspectableContainer container = new InspectableContainer();
        OmissionComponent component = new OmissionComponent(
            new OmissionDriver(container)
        );
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build()
            .start();

        try {
            assertThat(container.getLogConsumers()).isEmpty();
        } finally {
            environment.close();
        }
    }

    @Test
    void shouldNotMaterializeAnUnterminatedMultimegabyteStreamWithoutSubscribers() {
        InspectableContainer container = new InspectableContainer();
        OmissionComponent component = new OmissionComponent(
            new OmissionDriver(container)
        );
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build()
            .start();
        AtomicInteger materializations = new AtomicInteger();

        try {
            container.emitIfSubscribed(() -> {
                materializations.incrementAndGet();
                return ("x".repeat(8 * 1024 * 1024) + UNTERMINATED_SECRET)
                    .getBytes(StandardCharsets.UTF_8);
            });

            assertThat(container.getLogConsumers()).isEmpty();
            assertThat(materializations).hasValue(0);
            assertThat(environment.diagnostics().content())
                .doesNotContain(UNTERMINATED_SECRET);
        } finally {
            environment.close();
        }
    }

    @Test
    void shouldOmitAContainerStartupFailureMessageFromDefaultDiagnostics() {
        FailingDriver driver = new FailingDriver();
        FailingComponent component = new FailingComponent(driver);
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build();

        EnvironmentStartException failure = catchThrowableOfType(
            environment::start,
            EnvironmentStartException.class
        );

        assertThat(failure.getMessage()).isEqualTo("Environment startup failed");
        assertThat(failure.diagnostics().content())
            .contains("IllegalStateException")
            .doesNotContain("startup-token-canary-4f95b4e7", "second-secret-line");
        assertThat(environment.diagnostics().content())
            .isEqualTo(failure.diagnostics().content());
        assertThat(driver.container.getLogConsumers()).isEmpty();
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class OmissionComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private OmissionComponent(ComponentDriver<EmptyConfig, Void> driver) {
            super(
                ComponentId.component(ComponentType.of("omitted-container-output")),
                new EmptyConfig(),
                Void.class,
                driver
            );
        }
    }

    private static final class OmissionDriver
        extends TestcontainersDriver<EmptyConfig, Void, OmissionComponent> {
        private final InspectableContainer container;

        private OmissionDriver(InspectableContainer container) {
            super(OmissionComponent.class);
            this.container = container;
        }

        @Override
        protected ContainerPlan create(OmissionComponent component, DriverContext context) {
            return ContainerPlan.container(container).build();
        }
    }

    private static final class InspectableContainer
        extends GenericContainer<InspectableContainer> {
        private InspectableContainer() {
            super(DockerImageName.parse("unused:latest"));
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        private void emitIfSubscribed(Supplier<byte[]> output) {
            if (getLogConsumers().isEmpty()) {
                return;
            }
            OutputFrame frame = new OutputFrame(
                OutputFrame.OutputType.STDOUT,
                output.get()
            );
            getLogConsumers().forEach(consumer -> consumer.accept(frame));
        }
    }

    private static final class FailingComponent extends AbstractComponent<EmptyConfig, Void> {
        private FailingComponent(ComponentDriver<EmptyConfig, Void> driver) {
            super(
                ComponentId.component(ComponentType.of("failing-container")),
                new EmptyConfig(),
                Void.class,
                driver
            );
        }
    }

    private static final class FailingDriver
        extends TestcontainersDriver<EmptyConfig, Void, FailingComponent> {
        private final FailingContainer container = new FailingContainer();

        private FailingDriver() {
            super(FailingComponent.class);
        }

        @Override
        protected ContainerPlan create(FailingComponent component, DriverContext context) {
            return ContainerPlan.container(container).build();
        }
    }

    private static final class FailingContainer extends GenericContainer<FailingContainer> {
        private FailingContainer() {
            super(DockerImageName.parse("unused:latest"));
        }

        @Override
        public void start() {
            throw new IllegalStateException(STARTUP_SECRET);
        }
    }
}
