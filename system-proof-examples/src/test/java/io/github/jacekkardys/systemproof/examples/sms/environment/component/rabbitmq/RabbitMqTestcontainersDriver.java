package io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq.RabbitMqConfig.Driver;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq.RabbitMqConfig.Runtime;
import io.github.jacekkardys.systemproof.model.endpoint.AmqpEndpoint;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;

public final class RabbitMqTestcontainersDriver
    extends TestcontainersDriver<Runtime, Void, RabbitMqComponent> {
    private final Driver configuration;

    public RabbitMqTestcontainersDriver(@NonNull Driver configuration) {
        super(RabbitMqComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(RabbitMqComponent component, DriverContext context) {
        Runtime componentConfiguration = component.configuration();
        PortBinding amqpPort = port(configuration.amqpPort());
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(configuration.image()))
            .withEnv(configuration.usernameVariable(), componentConfiguration.username())
            .withEnv(configuration.passwordVariable(), componentConfiguration.password().reveal())
            .withEnv(configuration.virtualHostVariable(), componentConfiguration.virtualHost())
            .waitingFor(Wait.forListeningPort().withStartupTimeout(configuration.startupTimeout()));
        return ContainerPlan.container(container)
            .provides(
                component.amqp(),
                amqpPort,
                address -> new AmqpEndpoint(
                    address.host(),
                    address.port(),
                    componentConfiguration.virtualHost(),
                    componentConfiguration.username(),
                    componentConfiguration.password()
                )
            )
            .build();
    }
}
