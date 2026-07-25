package io.github.jacekkardys.systemproof.examples.sms.environment.component.redis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.model.DriverConfig;

public interface RedisConfig
    extends ComponentConfig<RedisConfig.Driver> {

    @PositiveOrZero(message = "Redis database ID must not be negative")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_REDIS_DATABASE_ID",
        defaultValue = "1"
    )
    int databaseId();

    public interface Driver extends DriverConfig {
        @NotBlank(message = "Redis image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_REDIS_IMAGE",
            defaultValue = "redis:8.0.3-alpine"
        )
        String image();

        @Positive(message = "Redis port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "6379")
        int port();

        @NotNull(message = "Redis startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
