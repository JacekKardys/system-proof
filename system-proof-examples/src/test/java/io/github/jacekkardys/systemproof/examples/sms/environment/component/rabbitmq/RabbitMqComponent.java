package io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_AMQP;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.communication.Communication;
import io.github.jacekkardys.systemproof.model.topology.PortContract;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.component.SystemComponent;
import io.github.jacekkardys.systemproof.model.endpoint.AmqpEndpoint;

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
