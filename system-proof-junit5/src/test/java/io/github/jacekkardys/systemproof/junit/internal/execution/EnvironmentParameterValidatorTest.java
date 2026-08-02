package io.github.jacekkardys.systemproof.junit.internal.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ParameterResolutionException;

class EnvironmentParameterValidatorTest {

    private final EnvironmentParameterValidator validator =
        new EnvironmentParameterValidator();

    @Test
    void shouldAcceptExactlyOneConcreteEnvironmentParameterAndUnrelatedParameters()
        throws Exception {
        val testMethod = Scenario.class.getDeclaredMethod(
            "valid",
            TestEnvironment.class,
            String.class
        );

        assertThatCode(() -> validator.validateConfiguration(testMethod, TestEnvironment.class))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptAMethodWithoutAnEnvironmentParameter() throws Exception {
        val testMethod = Scenario.class.getDeclaredMethod("withoutEnvironment", String.class);

        assertThatCode(() -> validator.validateConfiguration(testMethod, TestEnvironment.class))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectABroaderEnvironmentParameter() throws Exception {
        val testMethod = Scenario.class.getDeclaredMethod("broader", Environment.class);

        assertThatThrownBy(() -> validator.validateConfiguration(
            testMethod,
            TestEnvironment.class
        ))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                Scenario.class.getName() + "#broader",
                "exact type " + TestEnvironment.class.getName(),
                Environment.class.getName()
            );
    }

    @Test
    void shouldRejectMultipleEnvironmentParameters() throws Exception {
        val testMethod = Scenario.class.getDeclaredMethod(
            "multiple",
            TestEnvironment.class,
            TestEnvironment.class
        );

        assertThatThrownBy(() -> validator.validateConfiguration(
            testMethod,
            TestEnvironment.class
        ))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                Scenario.class.getName() + "#multiple",
                "at most one",
                TestEnvironment.class.getName()
            );
    }

    @Test
    void shouldRejectAnAdditionalAssignableEnvironmentParameter() throws Exception {
        val testMethod = Scenario.class.getDeclaredMethod(
            "ambiguous",
            TestEnvironment.class,
            Environment.class
        );

        assertThatThrownBy(() -> validator.validateResolution(
            testMethod,
            TestEnvironment.class
        ))
            .isInstanceOf(ParameterResolutionException.class)
            .hasMessageContaining(
                Scenario.class.getName() + "#ambiguous",
                "at most one",
                "exact type"
            );
    }

    private static final class Scenario {
        @SuppressWarnings("unused")
        void valid(TestEnvironment environment, String unrelated) {}

        @SuppressWarnings("unused")
        void withoutEnvironment(String unrelated) {}

        @SuppressWarnings("unused")
        void broader(Environment environment) {}

        @SuppressWarnings("unused")
        void multiple(TestEnvironment first, TestEnvironment second) {}

        @SuppressWarnings("unused")
        void ambiguous(TestEnvironment exact, Environment assignable) {}
    }

    private static final class TestEnvironment extends Environment {
        private TestEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
            super(topology, logging);
        }
    }
}
