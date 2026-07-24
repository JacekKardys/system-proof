package pl.gov.il.test.harness.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import pl.gov.il.test.harness.driver.ComponentDriver;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.RuntimeConfig;
import pl.gov.il.test.harness.model.ComponentId;
import pl.gov.il.test.harness.model.ComponentType;

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
    void shouldValidateConfigurationWhenComponentIsDeclared() {
        ConstraintViolationException failure = catchThrowableOfType(
            ConstraintViolationException.class,
            () -> new TestComponent(new TestConfiguration(" "))
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
        private TestComponent(TestConfiguration configuration) {
            super(
                ComponentId.component(ComponentType.of("test")),
                configuration,
                Void.class,
                UNUSED
            );
        }

        @Override
        protected ComponentType componentType() {
            return ComponentType.of("test");
        }
    }
}
