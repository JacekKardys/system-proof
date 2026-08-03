package io.github.jacekkardys.systemproof.examples.sms.environment.component.redis;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_REDIS;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.communication.Communication;
import io.github.jacekkardys.systemproof.topology.PortContract;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.component.SystemComponent;
import io.github.jacekkardys.systemproof.endpoint.RedisEndpoint;

@SystemComponent(type = "redis", driver = RedisTestcontainersDriver.class)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisComponent extends AbstractComponent<RedisConfig, Void> {

    @PortContract(JASMIN_REDIS)
    @Communication.Redis
    private ProvidedPort<RedisEndpoint> redis;

}
