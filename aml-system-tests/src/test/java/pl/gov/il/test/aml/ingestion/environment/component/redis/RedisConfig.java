package pl.gov.il.test.aml.ingestion.environment.component.redis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import pl.gov.il.test.harness.configuration.ComponentConfig;
import pl.gov.il.test.harness.configuration.ConfigurationSource;
import pl.gov.il.test.harness.configuration.EnvironmentVariable;
import pl.gov.il.test.harness.configuration.Literal;
import pl.gov.il.test.harness.model.DriverConfig;
import pl.gov.il.test.harness.model.RuntimeConfig;

public interface RedisConfig
    extends ComponentConfig<RedisConfig.Runtime, RedisConfig.Driver> {

    public interface Runtime extends RuntimeConfig {
        @PositiveOrZero(message = "Redis database ID must not be negative")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_REDIS_DATABASE_ID",
            defaultValue = "1"
        )
        int databaseId();
    }

    public interface Driver extends DriverConfig {
        @NotBlank(message = "Redis image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_REDIS_IMAGE",
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
