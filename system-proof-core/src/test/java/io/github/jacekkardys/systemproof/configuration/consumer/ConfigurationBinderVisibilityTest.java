package io.github.jacekkardys.systemproof.configuration.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.model.EnvironmentConfiguration;

class ConfigurationBinderVisibilityTest {
    @Test
    void shouldBindAndValidatePrivateContractsDeclaredOutsideTheBinderPackage() {
        PrivateConfiguration configuration = EnvironmentConfiguration.of(Map.of())
            .bind(PrivateConfiguration.class);

        assertThat(configuration.value()).isEqualTo("configured");
    }

    private interface PrivateConfiguration {
        @NotBlank
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "value",
            defaultValue = "configured"
        )
        String value();
    }
}
