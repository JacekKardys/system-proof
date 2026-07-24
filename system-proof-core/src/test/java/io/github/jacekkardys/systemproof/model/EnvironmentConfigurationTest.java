package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentConfigurationTest {
    @Test
    void shouldReadRequiredOptionalDefaultAndDurationValues() {
        EnvironmentConfiguration configuration = EnvironmentConfiguration.of(Map.of(
            "required", "value",
            "retries", "3",
            "timeout", "PT3S"
        ));

        assertThat(configuration.required("required")).isEqualTo("value");
        assertThat(configuration.optional("missing")).isEmpty();
        assertThat(configuration.value("missing", "fallback")).isEqualTo("fallback");
        assertThat(configuration.integer("retries", 1)).isEqualTo(3);
        assertThat(configuration.duration("timeout", Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void shouldReportMissingAndMalformedValuesClearly() {
        EnvironmentConfiguration configuration = EnvironmentConfiguration.of(Map.of(
            "retries", "many",
            "timeout", "three"
        ));

        assertThatThrownBy(() -> configuration.required("missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Required environment configuration value 'missing' is missing");
        assertThatThrownBy(() -> configuration.integer("retries", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retries", "integer", "many");
        assertThatThrownBy(() -> configuration.duration("timeout", Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeout", "ISO-8601 duration", "three");
    }

    @Test
    void shouldRedactConfigurationAndSecretDiagnosticRepresentations() {
        EnvironmentConfiguration configuration = EnvironmentConfiguration.of(Map.of("password", "do-not-log"));
        Secret<String> secret = Secret.secret(configuration.required("password"));

        assertThat(configuration).hasToString("EnvironmentConfiguration[redacted]");
        assertThat(secret).hasToString("<redacted>");
        assertThat(secret.reveal()).isEqualTo("do-not-log");
    }
}
