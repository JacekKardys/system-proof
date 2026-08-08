package io.github.jacekkardys.systemproof.junit.internal;

import java.util.List;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;

final class EnvironmentDiagnosticsTestFixture {
    private EnvironmentDiagnosticsTestFixture() {}

    static EnvironmentDiagnostics capture() {
        return new TestEnvironment(
            EnvironmentTopology.of(List.of(new TestComponent()), List.of()),
            EnvironmentLogging.defaults()
        ).diagnostics();
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class TestComponent extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("diagnostics-fixture");

        private TestComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
        }
    }

    private static final class TestEnvironment extends Environment {
        private TestEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging
        ) {
            super(topology, logging);
        }
    }
}
