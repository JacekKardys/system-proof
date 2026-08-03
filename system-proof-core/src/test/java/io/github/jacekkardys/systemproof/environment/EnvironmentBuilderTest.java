package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.communication.Communication;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.DriverConfig;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.configuration.EnvironmentConfiguration;
import io.github.jacekkardys.systemproof.topology.PortContract;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.configuration.Secret;
import io.github.jacekkardys.systemproof.component.SystemComponent;

class EnvironmentBuilderTest {
    private static final Map<String, String> VALUES = Map.of(
        "TEST_COMPONENT_NAME", "configured-service",
        "TEST_DRIVER_IMAGE", "service:test"
    );

    @BeforeEach
    void resetDriverObservation() {
        ValidDriver.started.set(null);
    }

    @Test
    void shouldMaterializeAndRegisterAnAnnotatedComponentExactlyOnce() {
        EnvironmentBuilder builder = new EnvironmentBuilder(
            EnvironmentConfiguration.of(VALUES)
        );

        ValidComponent component = builder.component(ValidComponent.class);
        Environment environment = builder.build();

        assertThat(component.id()).isEqualTo(ComponentId.component(ComponentType.of("service")));
        assertThat(component.configuration().name()).isEqualTo("configured-service");
        assertThat(component.output).isNotNull();
        assertThat(component.ports()).containsExactly(component.output);
        assertThat(environment.components()).containsExactly(component);
        assertThat(environment.components().getFirst()).isSameAs(component);
        assertThat(component.driver())
            .isInstanceOfSatisfying(ValidDriver.class, driver ->
                assertThat(driver.configuration.image()).isEqualTo("service:test")
            );
    }

    @Test
    void shouldStartTheExactComponentInstanceReturnedByTheBuilder() {
        EnvironmentBuilder builder = new EnvironmentBuilder(
            EnvironmentConfiguration.of(VALUES)
        );
        ValidComponent component = builder.component(ValidComponent.class);

        try (Environment environment = builder.build().start()) {
            assertThat(ValidDriver.started.get()).isSameAs(component);
            assertThat(environment.components()).containsExactly(component);
        }
    }

    @Test
    void shouldCreateATypedFacadeFromValidatedConstructionResults() {
        EnvironmentBuilder builder = new EnvironmentBuilder(EnvironmentConfiguration.of(VALUES));
        ValidComponent component = builder.component(ValidComponent.class);

        TestEnvironment environment = builder.build(TestEnvironment::new);

        assertThat(environment.components()).containsExactly(component);
    }

    @Test
    void shouldMaterializeTwoQualifiedInstancesOfTheSameComponentType() {
        EnvironmentBuilder builder = new EnvironmentBuilder(
            EnvironmentConfiguration.of(VALUES)
        );

        ValidComponent primary = builder.component("primary", ValidComponent.class);
        ValidComponent secondary = builder.component("secondary", ValidComponent.class);
        Environment environment = builder.build();

        assertThat(primary.id())
            .isEqualTo(ComponentId.component(ComponentType.of("service"), "primary"));
        assertThat(secondary.id())
            .isEqualTo(ComponentId.component(ComponentType.of("service"), "secondary"));
        assertThat(environment.components()).containsExactly(primary, secondary);
    }

    @Test
    void shouldPreserveTheTypedManualMaterializationPath() {
        EnvironmentConfiguration values = EnvironmentConfiguration.of(VALUES);
        ValidConfig configuration = values.bind(ValidConfig.class);
        ComponentDriver<ValidConfig, Void> driver = (component, context) ->
            ComponentRuntime.<Void>runtime(() -> {}).build();
        EnvironmentBuilder builder = new EnvironmentBuilder(values);

        ValidComponent component = builder.component(
            "manual",
            ValidComponent.class,
            configuration,
            driver
        );

        assertThat(component.configuration()).isSameAs(configuration);
        assertThat(component.driver()).isSameAs(driver);
        assertThat(builder.build().components()).containsExactly(component);
    }

    @Test
    void shouldResolveDriverTypesFromAnInheritedDeclaration() {
        InheritedDriverComponent component = new EnvironmentBuilder(
            EnvironmentConfiguration.of(VALUES)
        ).component(InheritedDriverComponent.class);

        assertThat(component.driver()).isInstanceOf(InheritedDriver.class);
        assertThat(component.configuration().name()).isEqualTo("configured-service");
    }

    @Test
    void shouldIgnoreAnUnrelatedThirdDriverBaseTypeArgument() {
        RetryingDriverComponent component = new EnvironmentBuilder(
            EnvironmentConfiguration.of(VALUES)
        ).component(RetryingDriverComponent.class);

        assertThat(component.driver()).isInstanceOf(RetryingDriver.class);
        assertThat(component.configuration().name()).isEqualTo("configured-service");
    }

    @Test
    void shouldRejectAComponentWithoutSystemComponentMetadata() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(MissingAnnotationComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                MissingAnnotationComponent.class.getName(),
                "must declare @SystemComponent"
            );
    }

    @Test
    void shouldRejectABlankComponentType() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(BlankTypeComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                BlankTypeComponent.class.getName(),
                "blank @SystemComponent type"
            );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectANonConcreteComponentConfigurationType() {
        assertThatThrownBy(() -> new EnvironmentBuilder().component((Class) GenericComponent.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                GenericComponent.class.getName(),
                "concrete component configuration type"
            );
    }

    @Test
    void shouldRejectAConfigurationWithoutComponentConfigAssociation() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(UnassociatedConfigurationComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                UnassociatedConfigurationComponent.class.getName(),
                UnassociatedConfiguration.class.getName(),
                "must directly implement ComponentConfig<D>"
            );
    }

    @Test
    void shouldRejectADriverWithAnotherComponentConfigurationType() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(WrongConfigurationComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                WrongConfigurationComponent.class.getName(),
                WrongConfigurationDriver.class.getName(),
                OtherConfig.class.getName(),
                ValidConfig.class.getName()
            );
    }

    @Test
    void shouldRejectADriverWithAnotherOperationsType() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(WrongOperationsComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                WrongOperationsComponent.class.getName(),
                WrongOperationsDriver.class.getName(),
                String.class.getName(),
                Void.class.getName()
            );
    }

    @Test
    void shouldRejectAnAmbiguousDriverConfigurationConstructor() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(AmbiguousDriverComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                AmbiguousDriverComponent.class.getName(),
                AmbiguousDriver.class.getName(),
                ValidConfig.Driver.class.getName(),
                "exactly one unambiguous constructor"
            );
    }

    @Test
    void shouldRejectAComponentWithoutANoArgumentConstructor() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder().component(MissingConstructorComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                MissingConstructorComponent.class.getName(),
                "must declare a no-argument constructor"
            );
    }

    @Test
    void shouldReportDriverCreationWithoutLeakingSecrets() {
        assertThatThrownBy(() ->
            new EnvironmentBuilder(
                EnvironmentConfiguration.of(VALUES)
            ).component(FailingDriverComponent.class)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                FailingDriverComponent.class.getName(),
                FailingDriver.class.getName(),
                IllegalStateException.class.getName()
            )
            .hasMessageNotContaining("component-secret")
            .hasNoCause();
    }

    @Test
    void shouldNotRegisterAPartiallyMaterializedComponent() {
        EnvironmentBuilder builder = new EnvironmentBuilder(
            EnvironmentConfiguration.of(VALUES)
        );

        assertThatThrownBy(() -> builder.component(InvalidPortComponent.class))
            .hasMessageContaining(
                InvalidPortComponent.class.getName() + ".output",
                "must declare @PortContract"
            );
        ValidComponent valid = builder.component(ValidComponent.class);

        assertThat(builder.build().components()).containsExactly(valid);
    }

    public interface ValidConfig extends ComponentConfig<ValidConfig.Driver> {
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "TEST_COMPONENT_NAME"
        )
        String name();

        @ConfigurationSource(provider = Literal.class, value = "component-secret")
        Secret<String> secret();

        interface Driver extends DriverConfig {
            @ConfigurationSource(
                provider = EnvironmentVariable.class,
                key = "TEST_DRIVER_IMAGE"
            )
            String image();
        }
    }

    private interface OtherConfig extends ComponentConfig<ValidConfig.Driver> {}

    private interface UnassociatedConfiguration extends RuntimeConfig {}

    @SystemComponent(type = "service", driver = ValidDriver.class)
    private static final class ValidComponent extends AbstractComponent<ValidConfig, Void> {
        @PortContract("output")
        @Communication.Http
        private ProvidedPort<String> output;

        private ValidComponent() {}
    }

    private static final class ValidDriver implements ComponentDriver<ValidConfig, Void> {
        private static final AtomicReference<ValidComponent> started = new AtomicReference<>();

        private final ValidConfig.Driver configuration;

        private ValidDriver(ValidConfig.Driver configuration) {
            this.configuration = configuration;
        }

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<ValidConfig, Void> component,
            DriverContext context
        ) {
            ValidComponent valid = ValidComponent.class.cast(component);
            started.set(valid);
            return ComponentRuntime.<Void>runtime(() -> {})
                .provides(
                    valid.output,
                    EndpointBinding.binding("internal-output", "external-output")
                )
                .build();
        }
    }

    @SystemComponent(type = "inherited-driver", driver = InheritedDriver.class)
    private static final class InheritedDriverComponent
        extends AbstractComponent<ValidConfig, Void> {
        private InheritedDriverComponent() {}
    }

    private static class FixedBaseDriver implements ComponentDriver<ValidConfig, Void> {
        protected FixedBaseDriver() {}

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<ValidConfig, Void> component,
            DriverContext context
        ) {
            return ComponentRuntime.<Void>runtime(() -> {}).build();
        }
    }

    private static final class InheritedDriver extends FixedBaseDriver {
        private InheritedDriver(ValidConfig.Driver configuration) {}
    }

    @SystemComponent(type = "retrying-driver", driver = RetryingDriver.class)
    private static final class RetryingDriverComponent
        extends AbstractComponent<ValidConfig, Void> {
        private RetryingDriverComponent() {}
    }

    private static class GenericDriverBase<C extends RuntimeConfig, O, M>
        implements ComponentDriver<C, O> {
        protected GenericDriverBase() {}

        @Override
        public ComponentRuntime<O> start(
            AbstractComponent<C, O> component,
            DriverContext context
        ) {
            return ComponentRuntime.<O>runtime(() -> {}).build();
        }
    }

    private static final class RetryPolicy {}

    private static final class RetryingDriver
        extends GenericDriverBase<ValidConfig, Void, RetryPolicy> {
        private RetryingDriver(ValidConfig.Driver configuration) {}
    }

    private static final class MissingAnnotationComponent
        extends AbstractComponent<ValidConfig, Void> {
        private MissingAnnotationComponent() {}
    }

    @SystemComponent(type = " ", driver = ValidDriver.class)
    private static final class BlankTypeComponent extends AbstractComponent<ValidConfig, Void> {
        private BlankTypeComponent() {}
    }

    @SystemComponent(type = "generic", driver = ValidDriver.class)
    private static final class GenericComponent<C extends RuntimeConfig>
        extends AbstractComponent<C, Void> {
        private GenericComponent() {}
    }

    @SystemComponent(type = "unassociated", driver = UnassociatedDriver.class)
    private static final class UnassociatedConfigurationComponent
        extends AbstractComponent<UnassociatedConfiguration, Void> {
        private UnassociatedConfigurationComponent() {}
    }

    private static final class UnassociatedDriver
        implements ComponentDriver<UnassociatedConfiguration, Void> {
        private UnassociatedDriver(ValidConfig.Driver configuration) {}

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<UnassociatedConfiguration, Void> component,
            DriverContext context
        ) {
            throw new AssertionError("Driver should not run");
        }
    }

    @SystemComponent(type = "wrong-configuration", driver = WrongConfigurationDriver.class)
    private static final class WrongConfigurationComponent
        extends AbstractComponent<ValidConfig, Void> {
        private WrongConfigurationComponent() {}
    }

    private static final class WrongConfigurationDriver
        implements ComponentDriver<OtherConfig, Void> {
        private WrongConfigurationDriver(ValidConfig.Driver configuration) {}

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<OtherConfig, Void> component,
            DriverContext context
        ) {
            throw new AssertionError("Driver should not run");
        }
    }

    @SystemComponent(type = "wrong-operations", driver = WrongOperationsDriver.class)
    private static final class WrongOperationsComponent
        extends AbstractComponent<ValidConfig, Void> {
        private WrongOperationsComponent() {}
    }

    private static final class WrongOperationsDriver
        implements ComponentDriver<ValidConfig, String> {
        private WrongOperationsDriver(ValidConfig.Driver configuration) {}

        @Override
        public ComponentRuntime<String> start(
            AbstractComponent<ValidConfig, String> component,
            DriverContext context
        ) {
            throw new AssertionError("Driver should not run");
        }
    }

    @SystemComponent(type = "ambiguous-driver", driver = AmbiguousDriver.class)
    private static final class AmbiguousDriverComponent
        extends AbstractComponent<ValidConfig, Void> {
        private AmbiguousDriverComponent() {}
    }

    private static final class AmbiguousDriver implements ComponentDriver<ValidConfig, Void> {
        private AmbiguousDriver(ValidConfig.Driver configuration) {}

        private AmbiguousDriver(DriverConfig configuration) {}

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<ValidConfig, Void> component,
            DriverContext context
        ) {
            throw new AssertionError("Driver should not run");
        }
    }

    @SystemComponent(type = "missing-constructor", driver = ValidDriver.class)
    private static final class MissingConstructorComponent
        extends AbstractComponent<ValidConfig, Void> {
        private MissingConstructorComponent(String name) {}
    }

    @SystemComponent(type = "failing-driver", driver = FailingDriver.class)
    private static final class FailingDriverComponent
        extends AbstractComponent<ValidConfig, Void> {
        private FailingDriverComponent() {}
    }

    private static final class FailingDriver implements ComponentDriver<ValidConfig, Void> {
        private FailingDriver(ValidConfig.Driver configuration) {
            throw new IllegalStateException("driver failed with component-secret");
        }

        @Override
        public ComponentRuntime<Void> start(
            AbstractComponent<ValidConfig, Void> component,
            DriverContext context
        ) {
            throw new AssertionError("Driver should not run");
        }
    }

    @SystemComponent(type = "invalid-port", driver = ValidDriver.class)
    private static final class InvalidPortComponent extends AbstractComponent<ValidConfig, Void> {
        @Communication.Http
        private ProvidedPort<String> output;

        private InvalidPortComponent() {}
    }

    private static final class TestEnvironment extends Environment {
        private TestEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }
    }
}
