package io.github.jacekkardys.systemproof.examples.sms.environment.component.redis;

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
import io.github.jacekkardys.systemproof.model.endpoint.RedisEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisComponent extends AbstractComponent<RedisConfig.Runtime, Void> {

    @Communication.Redis
    private ProvidedPort<RedisEndpoint> redis;

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("redis");
    }

    public static RedisComponent define(@NonNull ComponentFactory components) {
        return components.create(
            RedisComponent.class,
            RedisConfig.class,
            RedisTestcontainersDriver::new
        );
    }

    public static RedisComponent container(
        String qualifier,
        RedisConfig.Runtime configuration,
        RedisConfig.Driver driverConfiguration
    ) {
        return ComponentFactory.create(
            RedisComponent.class,
            qualifier,
            configuration,
            new RedisTestcontainersDriver(driverConfiguration)
        );
    }
}
