package io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.model.component.DriverConfig;

public interface SmsIngestionConfig
    extends ComponentConfig<SmsIngestionConfig.Driver> {

    @NotBlank(message = "SMS ingestion path must not be blank")
    @Pattern(regexp = "^/.*", message = "SMS ingestion path must start with '/'")
    @ConfigurationSource(provider = Literal.class, value = "/v1/ingestion/sms")
    String smsPath();

    public interface Driver extends DriverConfig {
        @NotBlank(message = "SMS ingestion image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_INGESTION_IMAGE",
            defaultValue = "system-proof-ingestion-service:local"
        )
        String image();

        @Positive(message = "SMS ingestion HTTP port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "8080")
        int httpPort();

        @NotBlank(message = "database URL environment variable must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_INGESTION_DATABASE_URL_VARIABLE",
            defaultValue = "DATABASE_URL"
        )
        String databaseUrlVariable();

        @NotBlank(message = "database username environment variable must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_INGESTION_DATABASE_USERNAME_VARIABLE",
            defaultValue = "DATABASE_USERNAME"
        )
        String databaseUsernameVariable();

        @NotBlank(message = "database password environment variable must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_INGESTION_DATABASE_PASSWORD_VARIABLE",
            defaultValue = "DATABASE_PASSWORD"
        )
        String databasePasswordVariable();

        @NotBlank(message = "SMS ingestion readiness path must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "/actuator/health/readiness")
        String readinessPath();

        @Positive(message = "SMS ingestion readiness status must be positive")
        @ConfigurationSource(provider = Literal.class, value = "200")
        int readinessStatus();

        @NotNull(message = "SMS ingestion startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
