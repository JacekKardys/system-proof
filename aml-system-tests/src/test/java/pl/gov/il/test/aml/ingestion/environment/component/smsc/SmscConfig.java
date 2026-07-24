package pl.gov.il.test.aml.ingestion.environment.component.smsc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import pl.gov.il.test.harness.configuration.ComponentConfig;
import pl.gov.il.test.harness.configuration.ConfigurationSource;
import pl.gov.il.test.harness.configuration.EnvironmentVariable;
import pl.gov.il.test.harness.configuration.Literal;
import pl.gov.il.test.harness.model.DriverConfig;
import pl.gov.il.test.harness.model.RuntimeConfig;
import pl.gov.il.test.harness.model.Secret;

public interface SmscConfig
    extends ComponentConfig<SmscConfig.Runtime, SmscConfig.Driver> {

    public interface Runtime extends RuntimeConfig {
        @NotBlank(message = "SMPP system ID must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_SMSC_SYSTEM_ID",
            defaultValue = "aml-test"
        )
        String systemId();

        @NotNull(message = "SMPP password must not be null")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_SMSC_PASSWORD",
            defaultValue = "password"
        )
        Secret<String> password();

        @NotBlank(message = "SMSC control path must not be blank")
        @Pattern(regexp = "^/.*", message = "SMSC control path must start with '/'")
        @ConfigurationSource(provider = Literal.class, value = "/test/messages")
        String controlPath();
    }

    public interface Driver extends DriverConfig {
        @NotBlank(message = "SMSC simulator image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_SMSC_SIMULATOR_IMAGE",
            defaultValue = "aml-smsc-simulator:local"
        )
        String image();

        @Positive(message = "SMSC SMPP port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "2775")
        int smppPort();

        @Positive(message = "SMSC control port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "8081")
        int controlPort();

        @NotBlank(message = "SMSC system ID environment variable must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "SMSC_SYSTEM_ID")
        String systemIdVariable();

        @NotBlank(message = "SMSC password environment variable must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "SMSC_PASSWORD")
        String passwordVariable();

        @NotBlank(message = "SMSC health path must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "/health")
        String healthPath();

        @Positive(message = "SMSC health status must be positive")
        @ConfigurationSource(provider = Literal.class, value = "200")
        int healthStatus();

        @NotNull(message = "SMSC startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
