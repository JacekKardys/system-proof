package io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Communication;
import io.github.jacekkardys.systemproof.model.ComponentFactory;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.endpoint.AmqpEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RabbitMqComponent
    extends AbstractComponent<RabbitMqConfig.Runtime, Void> {

    @Communication.Amqp
    private ProvidedPort<AmqpEndpoint> amqp;

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("rabbitmq");
    }

    public static RabbitMqComponent define(@NonNull ComponentFactory components) {
        return components.create(
            RabbitMqComponent.class,
            RabbitMqConfig.class,
            RabbitMqTestcontainersDriver::new
        );
    }

    public static RabbitMqComponent container(
        String qualifier,
        RabbitMqConfig.Runtime configuration,
        RabbitMqConfig.Driver driverConfiguration
    ) {
        return ComponentFactory.create(
            RabbitMqComponent.class,
            qualifier,
            configuration,
            new RabbitMqTestcontainersDriver(driverConfiguration)
        );
    }
}
