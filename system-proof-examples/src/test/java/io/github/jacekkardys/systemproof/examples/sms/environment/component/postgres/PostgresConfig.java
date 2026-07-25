package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.model.DriverConfig;
import io.github.jacekkardys.systemproof.model.Secret;

public interface PostgresConfig extends ComponentConfig<PostgresConfig.Driver> {

    @NotBlank(message = "database username must not be blank")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_DATABASE_USERNAME",
        defaultValue = "system_proof_example"
    )
    String username();

    @NotNull(message = "database password must not be null")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_DATABASE_PASSWORD",
        defaultValue = "system-proof-example-password"
    )
    Secret<String> password();

    @NotBlank(message = "database name must not be blank")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_DATABASE_NAME",
        defaultValue = "system_proof_example_ingestion"
    )
    String database();

    interface Driver extends DriverConfig {

        @NotBlank(message = "Postgres image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_POSTGRES_IMAGE",
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
