package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.model.DriverConfig;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.Secret;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

interface PostgresConfig extends ComponentConfig<PostgresConfig.Runtime, PostgresConfig.Driver> {

    interface Runtime extends RuntimeConfig {

        @NotBlank(message = "database username must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "example")
        String username();

        @NotNull(message = "database password must not be null")
        @ConfigurationSource(provider = Literal.class, value = "example-password")
        Secret<String> password();

        @NotBlank(message = "database name must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "example")
        String database();
    }

    interface Driver extends DriverConfig {

        @NotBlank(message = "PostgreSQL image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_POSTGRES_IMAGE",
            defaultValue = "postgres:17.6-alpine"
        )
        String image();

        @Positive(message = "PostgreSQL JDBC port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "5432")
        int jdbcPort();

        @NotBlank(message = "PostgreSQL compatible image must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "postgres")
        String compatibleImage();

        @NotNull(message = "PostgreSQL startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
