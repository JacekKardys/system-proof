package io.github.jacekkardys.systemproof.testcontainers.component;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.DriverConfig;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.model.SystemComponent;

class ComponentDriverMetadataTest {

    @Test
    void shouldRejectATestcontainersDriverBoundToAnotherComponentClass() {
        assertThatThrownBy(() ->
            Environment.environment().component(DeclaredComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                DeclaredComponent.class.getName(),
                WrongTargetDriver.class.getName(),
                OtherComponent.class.getName()
            );
    }

    private interface TestConfig extends ComponentConfig<TestConfig.Driver> {
        interface Driver extends DriverConfig {}
    }

    @SystemComponent(type = "declared", driver = WrongTargetDriver.class)
    private static final class DeclaredComponent extends AbstractComponent<TestConfig, Void> {
        private DeclaredComponent() {}
    }

    private static final class OtherComponent extends AbstractComponent<TestConfig, Void> {
        private OtherComponent() {}
    }

    private static final class WrongTargetDriver
        extends TestcontainersDriver<TestConfig, Void, OtherComponent> {

        private WrongTargetDriver(TestConfig.Driver configuration) {
            super(OtherComponent.class);
        }

        @Override
        protected ContainerPlan create(OtherComponent component, DriverContext context) {
            throw new AssertionError("Driver should not run");
        }
    }
}
