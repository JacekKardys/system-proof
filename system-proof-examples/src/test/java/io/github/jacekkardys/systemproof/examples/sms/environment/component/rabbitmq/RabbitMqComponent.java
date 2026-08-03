package io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_AMQP;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.communication.Communication;
import io.github.jacekkardys.systemproof.topology.PortContract;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.component.SystemComponent;
import io.github.jacekkardys.systemproof.endpoint.AmqpEndpoint;

@SystemComponent(type = "rabbitmq", driver = RabbitMqTestcontainersDriver.class)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RabbitMqComponent
    extends AbstractComponent<RabbitMqConfig, Void> {

    @PortContract(JASMIN_AMQP)
    @Communication.Amqp
    private ProvidedPort<AmqpEndpoint> amqp;

}
