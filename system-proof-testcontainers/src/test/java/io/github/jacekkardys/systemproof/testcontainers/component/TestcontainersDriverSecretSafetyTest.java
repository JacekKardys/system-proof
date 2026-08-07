package io.github.jacekkardys.systemproof.testcontainers.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
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
    }

    private record EmptyConfig() implements RuntimeConfig {}

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

        private FailingDriver() {
            super(FailingComponent.class);
        }

        @Override
        protected ContainerPlan create(FailingComponent component, DriverContext context) {
            return ContainerPlan.container(new FailingContainer()).build();
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
