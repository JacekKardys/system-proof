package pl.gov.il.test.aml.ingestion.environment.component.rabbitmq;

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

public interface RabbitMqConfig
    extends ComponentConfig<RabbitMqConfig.Runtime, RabbitMqConfig.Driver> {

    public interface Runtime extends RuntimeConfig {
        @NotBlank(message = "RabbitMQ username must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_RABBITMQ_USERNAME",
            defaultValue = "jasmin"
        )
        String username();

        @NotNull(message = "RabbitMQ password must not be null")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_RABBITMQ_PASSWORD",
            defaultValue = "jasmin-test-password"
        )
        Secret<String> password();

        @NotBlank(message = "RabbitMQ virtual host must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_RABBITMQ_VIRTUAL_HOST",
            defaultValue = "/jasmin"
        )
        String virtualHost();
    }

    public interface Driver extends DriverConfig {
        @NotBlank(message = "RabbitMQ image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_RABBITMQ_IMAGE",
            defaultValue = "rabbitmq:4.1.2-management-alpine"
        )
        String image();

        @Positive(message = "RabbitMQ AMQP port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "5672")
        int amqpPort();

        @NotBlank(message = "RabbitMQ username environment variable must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "RABBITMQ_DEFAULT_USER")
        String usernameVariable();

        @NotBlank(message = "RabbitMQ password environment variable must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "RABBITMQ_DEFAULT_PASS")
        String passwordVariable();

        @NotBlank(message = "RabbitMQ virtual host environment variable must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "RABBITMQ_DEFAULT_VHOST")
        String virtualHostVariable();

        @NotNull(message = "RabbitMQ startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
