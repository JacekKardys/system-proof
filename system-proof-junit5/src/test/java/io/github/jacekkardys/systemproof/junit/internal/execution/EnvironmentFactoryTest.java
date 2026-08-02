package io.github.jacekkardys.systemproof.junit.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;
import io.github.jacekkardys.systemproof.construction.EnvironmentTopology;

class EnvironmentFactoryTest {
    private final EnvironmentFactory factory = new EnvironmentFactory();

    @Test
    void shouldInvokeStaticZeroArgumentDefinitionOnTheEnvironmentFacade() {
        ZeroArguments.invocations = 0;

        assertThat(factory.create(ZeroArguments.class))
            .isInstanceOf(ZeroArguments.class);
        assertThat(ZeroArguments.invocations).isEqualTo(1);
    }

    @Test
    void shouldRejectMissingAndMultipleDefinitionsWithExpectedAndActualSignatures() {
        assertThatThrownBy(() -> factory.create(Missing.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(Missing.class.getName(), "exactly one", "expected=", "actual=none");

        assertThatThrownBy(() -> factory.create(Multiple.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(Multiple.class.getName(), "first()", "second()", "expected=", "actual=");
    }

    @Test
    void shouldRejectInvalidDefinitionSignaturesAndEnvironmentTypes() {
        assertThatThrownBy(() -> factory.create(InstanceMethod.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                InstanceMethod.class.getName() + "#define",
                "static method"
            );
        assertThatThrownBy(() -> factory.create(Parameterized.class))
            .hasMessageContaining(
                Parameterized.class.getName() + "#define",
                "must not declare parameters",
                EnvironmentConfiguration.class.getName()
            );
        assertThatThrownBy(() -> factory.create(WrongReturn.class))
            .hasMessageContaining(
                WrongReturn.class.getName() + "#define",
                "return type must match",
                WrongReturn.class.getName()
            );
        assertThatThrownBy(() -> factory.create(BaseReturn.class))
            .hasMessageContaining(
                BaseReturn.class.getName() + "#define",
                "return type must match",
                BaseReturn.class.getName()
            );
        assertThatThrownBy(() -> factory.create(AbstractDefinition.class))
            .hasMessageContaining(
                AbstractDefinition.class.getName(),
                "environment type must be concrete"
            );
    }

    @Test
    void shouldRejectNullBeforeAnyEnvironmentCanStart() {
        assertThatThrownBy(() -> factory.create(NullReturn.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                NullReturn.class.getName() + "#define",
                "returned null",
                "expected="
            );
    }

    private static final class ZeroArguments extends EnvironmentFixture {
        private static int invocations;

        private ZeroArguments(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static ZeroArguments define() {
            invocations++;
            return fixture(ZeroArguments::new);
        }
    }

    private static final class Missing extends EnvironmentFixture {
        private Missing(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }
    }

    private static final class Multiple extends EnvironmentFixture {
        private Multiple(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static Multiple first() {
            return fixture(Multiple::new);
        }

        @EnvironmentDefinition
        private static Multiple second() {
            return fixture(Multiple::new);
        }
    }

    private static final class InstanceMethod extends EnvironmentFixture {
        private InstanceMethod(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private InstanceMethod define() {
            return fixture(InstanceMethod::new);
        }
    }

    private static final class Parameterized extends EnvironmentFixture {
        private Parameterized(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static Parameterized define(EnvironmentConfiguration ignored) {
            return fixture(Parameterized::new);
        }
    }

    private static final class WrongReturn extends EnvironmentFixture {
        private WrongReturn(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static String define() {
            return "wrong";
        }
    }

    private static final class BaseReturn extends EnvironmentFixture {
        private BaseReturn(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static Environment define() {
            return fixture(BaseReturn::new);
        }
    }

    private abstract static class AbstractDefinition extends EnvironmentFixture {
        private AbstractDefinition(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static AbstractDefinition define() {
            return null;
        }
    }

    private static final class NullReturn extends EnvironmentFixture {
        private NullReturn(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }

        @EnvironmentDefinition
        private static NullReturn define() {
            return null;
        }
    }

    private abstract static class EnvironmentFixture extends Environment {
        private EnvironmentFixture(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }
    }

    private static <E extends EnvironmentFixture> E fixture(EnvironmentBuilder.EnvironmentCreator<E> creator) {
        return new EnvironmentBuilder()
            .components(new DummyComponent())
            .build(creator);
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class DummyComponent extends AbstractComponent<EmptyConfig, Void> {
        private static final ComponentType TYPE = ComponentType.of("dummy");

        private DummyComponent() {
            super(
                ComponentId.component(TYPE),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
        }

    }
}
