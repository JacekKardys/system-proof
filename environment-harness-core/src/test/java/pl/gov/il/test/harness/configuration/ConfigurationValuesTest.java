package pl.gov.il.test.harness.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfigurationValuesTest {
    @Test
    void shouldRequireNonNullValues() {
        Object value = new Object();

        assertThat(ConfigurationValues.requireNonNull(value, "setting")).isSameAs(value);
        assertThatNullPointerException()
            .isThrownBy(() -> ConfigurationValues.requireNonNull(null, "setting"))
            .withMessage("setting must not be null");
    }

    @Test
    void shouldRequireNonBlankText() {
        assertThat(ConfigurationValues.requireText("value", "setting")).isEqualTo("value");
        assertThatNullPointerException()
            .isThrownBy(() -> ConfigurationValues.requireText(null, "setting"))
            .withMessage("setting must not be null");
        assertThatThrownBy(() -> ConfigurationValues.requireText(" ", "setting"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("setting must not be blank");
    }
}
