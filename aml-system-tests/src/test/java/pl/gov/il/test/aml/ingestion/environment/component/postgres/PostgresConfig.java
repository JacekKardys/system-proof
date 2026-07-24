package pl.gov.il.test.aml.ingestion.environment.component.postgres;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import pl.gov.il.test.harness.configuration.ComponentConfig;
import pl.gov.il.test.harness.configuration.ConfigurationSource;
import pl.gov.il.test.harness.configuration.EnvironmentVariable;
import pl.gov.il.test.harness.configuration.Literal;
import pl.gov.il.test.harness.model.DriverConfig;
import pl.gov.il.test.harness.model.RuntimeConfig;
import pl.gov.il.test.harness.model.Secret;

public interface PostgresConfig extends ComponentConfig<PostgresConfig.Runtime, PostgresConfig.Driver> {

    interface Runtime extends RuntimeConfig {

        @NotBlank(message = "database username must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_DATABASE_USERNAME",
            defaultValue = "aml"
        )
        String username();

        @NotNull(message = "database password must not be null")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_DATABASE_PASSWORD",
            defaultValue = "aml-test-password"
        )
        Secret<String> password();

        @NotBlank(message = "database name must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_DATABASE_NAME",
            defaultValue = "aml_ingestion"
        )
        String database();
    }

    interface Driver extends DriverConfig {

        @NotBlank(message = "Postgres image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_POSTGRES_IMAGE",
            defaultValue = "postgres:17.6-alpine"
        )
        String image();

        @Positive(message = "Postgres JDBC port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "5432")
        int jdbcPort();

        @NotBlank(message = "Postgres compatible image must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "postgres")
        String compatibleImage();

        @NotNull(message = "Postgres startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
