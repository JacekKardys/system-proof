package io.github.jacekkardys.systemproof.examples.sms.environment.component.redis;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.redis.RedisConfig.Driver;
import io.github.jacekkardys.systemproof.endpoint.RedisEndpoint;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;

public final class RedisTestcontainersDriver
    extends TestcontainersDriver<RedisConfig, Void, RedisComponent> {
    private final Driver configuration;

    public RedisTestcontainersDriver(@NonNull Driver configuration) {
        super(RedisComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(RedisComponent component, DriverContext context) {
        PortBinding redisPort = port(configuration.port());
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(configuration.image()))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(configuration.startupTimeout()));
        return ContainerPlan.container(container)
            .provides(
                component.redis(),
                redisPort,
                address -> new RedisEndpoint(
                    address.host(),
                    address.port(),
                    component.configuration().databaseId()
                )
            )
            .build();
    }
}
