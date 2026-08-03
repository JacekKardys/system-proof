package io.github.jacekkardys.systemproof.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentType;

class ConfigurationValidatorTest {
    private static final ComponentDriver<TestConfiguration, Void> UNUSED =
        (component, context) -> {
            throw new AssertionError("Driver should not run");
        };

    @Test
    void shouldReturnValidConfigurationUnchanged() {
        TestConfiguration configuration = new TestConfiguration("value");

        assertThat(ConfigurationValidator.validate(configuration)).isSameAs(configuration);
    }

    @Test
    void shouldReportConstraintViolationsForInvalidConfiguration() {
        ConstraintViolationException failure = catchThrowableOfType(
            ConstraintViolationException.class,
            () -> ConfigurationValidator.validate(new TestConfiguration(" "))
        );

        assertInvalidValueViolation(failure);
    }

    @Test
    void shouldValidateConfigurationWhenComponentIsMaterialized() {
        ConstraintViolationException failure = catchThrowableOfType(
            ConstraintViolationException.class,
            () -> new EnvironmentBuilder().component(
                TestComponent.class,
                ComponentType.of("test"),
                null,
                new TestConfiguration(" "),
                UNUSED
            )
        );

        assertInvalidValueViolation(failure);
    }

    private static void assertInvalidValueViolation(ConstraintViolationException failure) {
        assertThat(failure.getConstraintViolations()).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("value");
            assertThat(violation.getMessage()).isEqualTo("value must not be blank");
        });
    }

    private record TestConfiguration(
        @NotBlank(message = "value must not be blank") String value
    ) implements RuntimeConfig {}

    private static final class TestComponent extends AbstractComponent<TestConfiguration, Void> {
        private TestComponent() {}
    }
}
