package io.github.jacekkardys.systemproof.engine;

import static io.github.jacekkardys.systemproof.api.EnvironmentLogging.logs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentType;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.logging.LogLevel;

class EnvironmentRuntimeTest {
    private static final ComponentType COMPONENT = ComponentType.of("component");

    @Test
    void shouldCreateCompleteRuntimesWithDefaultAndExplicitRouting() {
        EnvironmentTopology topology = EnvironmentTopology.of(List.of(), List.of());
        EnvironmentLogging logging = EnvironmentLogging.defaults();

        EnvironmentRuntime direct = EnvironmentRuntime.of(topology, logging);
        EnvironmentRuntime explicitlyRouted = EnvironmentRuntime.of(
            topology,
            logging,
            ConnectionRouting.direct()
        );

        assertThat(direct.state()).isEqualTo(EnvironmentState.DECLARED);
        assertThat(explicitlyRouted.state()).isEqualTo(EnvironmentState.DECLARED);
        direct.close();
        explicitlyRouted.close();
        assertThat(direct.state()).isEqualTo(EnvironmentState.STOPPED);
        assertThat(explicitlyRouted.state()).isEqualTo(EnvironmentState.STOPPED);
    }

    @Test
    void shouldRejectNullFactoryArguments() {
        EnvironmentTopology topology = EnvironmentTopology.of(List.of(), List.of());
        EnvironmentLogging logging = EnvironmentLogging.defaults();

        assertThatNullPointerException()
            .isThrownBy(() -> EnvironmentRuntime.of(null, logging))
            .withMessage("topology must not be null");
        assertThatNullPointerException()
            .isThrownBy(() -> EnvironmentRuntime.of(topology, null))
            .withMessage("logging must not be null");
        assertThatNullPointerException()
            .isThrownBy(() -> EnvironmentRuntime.of(topology, logging, null))
            .withMessage("routing must not be null");
    }

    @Test
    void shouldValidateLoggingAgainstTopologyAtTheFactoryBoundary() {
        TestComponent outside = new TestComponent();
        EnvironmentTopology topology = EnvironmentTopology.of(List.of(), List.of());
        EnvironmentLogging logging = logs()
            .componentLevel(outside, LogLevel.DEBUG)
            .build();

        assertThatThrownBy(() -> EnvironmentRuntime.of(topology, logging))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Logging configuration references component 'component' outside the environment"
            );
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class TestComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private TestComponent() {
            super(
                ComponentId.component(COMPONENT),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
        }
    }
}
