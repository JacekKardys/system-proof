package pl.gov.il.test.harness.configuration.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.gov.il.test.harness.configuration.ConfigurationSource;
import pl.gov.il.test.harness.configuration.EnvironmentVariable;
import pl.gov.il.test.harness.model.EnvironmentConfiguration;

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
