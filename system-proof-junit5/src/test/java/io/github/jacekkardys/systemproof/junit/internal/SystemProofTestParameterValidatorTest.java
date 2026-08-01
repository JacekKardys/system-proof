package io.github.jacekkardys.systemproof.junit.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

class SystemProofTestParameterValidatorTest {

    private final SystemProofTestParameterValidator validator =
        new SystemProofTestParameterValidator();

    @Test
    void shouldAcceptExactlyOneConcreteEnvironmentParameterAndUnrelatedParameters()
        throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod(
            "valid",
            TestEnvironment.class,
            String.class
        );

        assertThatCode(() -> validator.validate(testMethod, TestEnvironment.class))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMissingEnvironmentParameter() throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod("missing", Environment.class);

        assertThatThrownBy(() -> validator.validate(testMethod, TestEnvironment.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                Scenario.class.getName() + "#missing",
                "exactly one",
                TestEnvironment.class.getName()
            );
    }

    @Test
    void shouldRejectMultipleEnvironmentParameters() throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod(
            "multiple",
            TestEnvironment.class,
            TestEnvironment.class
        );

        assertThatThrownBy(() -> validator.validate(testMethod, TestEnvironment.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                Scenario.class.getName() + "#multiple",
                "exactly one",
                TestEnvironment.class.getName()
            );
    }

    @Test
    void shouldRejectAnAdditionalAssignableEnvironmentParameter() throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod(
            "ambiguous",
            TestEnvironment.class,
            Environment.class
        );

        assertThatThrownBy(() -> validator.validate(testMethod, TestEnvironment.class))
            .isInstanceOf(ExtensionConfigurationException.class)
            .hasMessageContaining(
                Scenario.class.getName() + "#ambiguous",
                "exactly one",
                "no other parameter assignable"
            );
    }

    private static final class Scenario {
        @SuppressWarnings("unused")
        void valid(TestEnvironment environment, String unrelated) {}

        @SuppressWarnings("unused")
        void missing(Environment environment) {}

        @SuppressWarnings("unused")
        void multiple(TestEnvironment first, TestEnvironment second) {}

        @SuppressWarnings("unused")
        void ambiguous(TestEnvironment exact, Environment assignable) {}
    }

    private static final class TestEnvironment extends Environment {
        private TestEnvironment() {
            super(Environment.environment());
        }
    }
}
