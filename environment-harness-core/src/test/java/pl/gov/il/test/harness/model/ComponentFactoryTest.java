package pl.gov.il.test.harness.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.gov.il.test.harness.model.AbstractComponent.component;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.gov.il.test.harness.configuration.ComponentConfig;
import pl.gov.il.test.harness.configuration.ConfigurationSource;
import pl.gov.il.test.harness.configuration.EnvironmentVariable;
import pl.gov.il.test.harness.driver.ComponentDriver;
import pl.gov.il.test.harness.driver.ComponentRuntime;
import pl.gov.il.test.harness.driver.DriverContext;

class ComponentFactoryTest {
    private static final ComponentType SERVICE = ComponentType.of("service");
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldCreateAConfiguredComponentThroughItsPrivateConstructor() {
        EmptyConfig configuration = new EmptyConfig();

        PassiveComponent component = component(
            PassiveComponent.class,
            "secondary",
            configuration,
            UNUSED
        );

        assertThat(component.id()).isEqualTo(ComponentId.component(SERVICE, "secondary"));
        assertThat(component.type()).isEqualTo(SERVICE);
        assertThat(component.configuration()).isSameAs(configuration);
        assertThat(component.driver()).isSameAs(UNUSED);
    }

    @Test
    void shouldCastOnlyExplicitlyDeclaredRuntimeOperations() {
        ComponentDriver<EmptyConfig, String> driver = (component, context) -> {
            throw new AssertionError("Driver should not run");
        };
        OperationalComponent component = component(
            OperationalComponent.class,
            null,
            new EmptyConfig(),
            driver
        );

        assertThat(component.castOperations("operations")).isEqualTo("operations");
        assertThatThrownBy(() -> component.castOperations(42))
            .isInstanceOf(ClassCastException.class);
    }

    @Test
    void shouldRejectRuntimeOperationsForAPassiveComponent() {
        PassiveComponent component = component(
            PassiveComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        );

        assertThatThrownBy(() -> component.castOperations(new Object()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Component 'service' (type=service) declares no runtime operations");
    }

    @Test
    void shouldBindComponentAndDriverConfigurationWhenCreatingAComponent() {
        EnvironmentConfiguration values = EnvironmentConfiguration.of(Map.of(
            "TEST_COMPONENT_NAME", "configured-service",
            "TEST_RUNTIME_IMAGE", "service:test"
        ));

        ConfiguredComponent component = ComponentFactory.from(values).create(
            ConfiguredComponent.class,
            "secondary",
            ConfiguredConfiguration.class,
            ConfiguredDriver::new
        );

        assertThat(component.id()).isEqualTo(ComponentId.component(SERVICE, "secondary"));
        assertThat(component.configuration().name()).isEqualTo("configured-service");
        assertThat(component.driver())
            .isInstanceOfSatisfying(ConfiguredDriver.class, driver ->
                assertThat(driver.configuration.image()).isEqualTo("service:test")
            );
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private interface ConfiguredRuntimeConfiguration extends RuntimeConfig {
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "TEST_COMPONENT_NAME"
        )
        String name();
    }

    private interface ConfiguredDriverConfiguration extends DriverConfig {
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "TEST_RUNTIME_IMAGE"
        )
        String image();
    }

    private interface ConfiguredConfiguration
        extends ComponentConfig<
            ConfiguredRuntimeConfiguration,
            ConfiguredDriverConfiguration
        > {}

    private static final class PassiveComponent extends AbstractComponent<EmptyConfig, Void> {
        private PassiveComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class ConfiguredComponent
        extends AbstractComponent<ConfiguredRuntimeConfiguration, Void> {
        private ConfiguredComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class ConfiguredDriver
        implements ComponentDriver<ConfiguredRuntimeConfiguration, Void> {
        private final ConfiguredDriverConfiguration configuration;

        private ConfiguredDriver(ConfiguredDriverConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<ConfiguredRuntimeConfiguration, Void> component,
            DriverContext context
        ) {
            throw new AssertionError("Driver should not run");
        }
    }

    private static final class OperationalComponent
        extends AbstractComponent<EmptyConfig, String> {
        private OperationalComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }
}
