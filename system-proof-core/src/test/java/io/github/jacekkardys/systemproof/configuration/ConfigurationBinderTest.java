package io.github.jacekkardys.systemproof.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;
import io.github.jacekkardys.systemproof.model.Secret;

class ConfigurationBinderTest {
    @Test
    void shouldBindTypedConfigurationFromExternalAndLiteralSources() {
        TestConfiguration configuration = EnvironmentConfiguration.of(Map.of(
            "name", "configured",
            "retries", "3",
            "mode", "receiver",
            "password", "do-not-log"
        )).bind(TestConfiguration.class);

        assertThat(configuration.name()).isEqualTo("configured");
        assertThat(configuration.retries()).isEqualTo(3);
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(configuration.mode()).isEqualTo(Mode.RECEIVER);
        assertThat(configuration.password().reveal()).isEqualTo("do-not-log");
        assertThat(configuration.label()).isEqualTo("configured:3");
        assertThat(configuration).hasToString("TestConfiguration[redacted]");
    }

    @Test
    void shouldUseExternalDefaults() {
        TestConfiguration configuration = EnvironmentConfiguration.of(Map.of())
            .bind(TestConfiguration.class);

        assertThat(configuration.name()).isEqualTo("fallback");
        assertThat(configuration.retries()).isEqualTo(1);
        assertThat(configuration.mode()).isEqualTo(Mode.TRANSCEIVER);
        assertThat(configuration.password().reveal()).isEqualTo("password");
    }

    @Test
    void shouldRequireExternalValuesWithoutDefaults() {
        assertThatThrownBy(() -> EnvironmentConfiguration.of(Map.of()).bind(RequiredConfiguration.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Required environment configuration value 'required' is missing");
    }

    @Test
    void shouldReportMalformedTypedValues() {
        assertThatThrownBy(() -> EnvironmentConfiguration.of(Map.of("retries", "many"))
            .bind(TestConfiguration.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retries", "integer", "many");
    }

    @Test
    void shouldValidateConstraintsDeclaredOnFluentConfigurationMethods() {
        assertThatThrownBy(() -> EnvironmentConfiguration.of(Map.of()).bind(InvalidConfiguration.class))
            .isInstanceOf(ConstraintViolationException.class)
            .satisfies(failure -> assertThat(
                ((ConstraintViolationException) failure).getConstraintViolations()
            ).singleElement().satisfies(violation ->
                assertThat(violation.getMessage()).isEqualTo("value must not be blank")
            ));
    }

    @Test
    void shouldRejectInvalidConfigurationContracts() {
        EnvironmentConfiguration environment = EnvironmentConfiguration.of(Map.of());

        assertThatThrownBy(() -> environment.bind(NotAnInterface.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be an interface");
        assertThatThrownBy(() -> environment.bind(UnannotatedConfiguration.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must declare @ConfigurationSource");
        assertThatThrownBy(() -> environment.bind(UnsupportedConfiguration.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported return type");
    }

    private interface TestConfiguration {
        @NotBlank
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "name",
            defaultValue = "fallback"
        )
        String name();

        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "retries",
            defaultValue = "1"
        )
        int retries();

        @ConfigurationSource(provider = Literal.class, value = "PT5S")
        Duration timeout();

        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "mode",
            defaultValue = "transceiver"
        )
        Mode mode();

        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "password",
            defaultValue = "password"
        )
        Secret<String> password();

        default String label() {
            return name() + ":" + retries();
        }
    }

    private interface RequiredConfiguration {
        @ConfigurationSource(provider = EnvironmentVariable.class, key = "required")
        String required();
    }

    private interface InvalidConfiguration {
        @NotBlank(message = "value must not be blank")
        @ConfigurationSource(provider = Literal.class, value = " ")
        String value();
    }

    private interface UnannotatedConfiguration {
        String value();
    }

    private interface UnsupportedConfiguration {
        @ConfigurationSource(provider = Literal.class, value = "1")
        long value();
    }

    private static final class NotAnInterface {}

    private enum Mode {
        TRANSCEIVER,
        RECEIVER
    }
}
