package io.github.jacekkardys.systemproof.examples.sms.environment.component.redis;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_REDIS;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Communication;
import io.github.jacekkardys.systemproof.model.PortContract;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.SystemComponent;
import io.github.jacekkardys.systemproof.model.endpoint.RedisEndpoint;

@SystemComponent(type = "redis", driver = RedisTestcontainersDriver.class)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisComponent extends AbstractComponent<RedisConfig, Void> {

    @PortContract(JASMIN_REDIS)
    @Communication.Redis
    private ProvidedPort<RedisEndpoint> redis;

}
