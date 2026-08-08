package io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import lombok.NonNull;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq.RabbitMqConfig.Driver;
import io.github.jacekkardys.systemproof.endpoint.AmqpEndpoint;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;

public final class RabbitMqTestcontainersDriver
    extends TestcontainersDriver<RabbitMqConfig, Void, RabbitMqComponent> {
    private final Driver configuration;

    public RabbitMqTestcontainersDriver(@NonNull Driver configuration) {
        super(RabbitMqComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(RabbitMqComponent component, DriverContext context) {
        RabbitMqConfig componentConfiguration = component.configuration();
        PortBinding amqpPort = port(configuration.amqpPort());
        return ContainerPlan.container(DockerImageName.parse(configuration.image()))
            .environment(configuration.usernameVariable(), componentConfiguration.username())
            .environment(
                configuration.passwordVariable(),
                componentConfiguration.password().reveal()
            )
            .environment(
                configuration.virtualHostVariable(),
                componentConfiguration.virtualHost()
            )
            .waitForListeningPorts(amqpPort)
            .readinessTimeout(configuration.startupTimeout())
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
