package pl.gov.il.test.aml.ingestion.environment.component.redis;

import static pl.gov.il.test.harness.testcontainers.component.PortBinding.port;

import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import pl.gov.il.test.aml.ingestion.environment.component.redis.RedisConfig.Driver;
import pl.gov.il.test.aml.ingestion.environment.component.redis.RedisConfig.Runtime;
import pl.gov.il.test.harness.model.endpoint.RedisEndpoint;
import pl.gov.il.test.harness.driver.DriverContext;
import pl.gov.il.test.harness.testcontainers.component.ContainerPlan;
import pl.gov.il.test.harness.testcontainers.component.PortBinding;
import pl.gov.il.test.harness.testcontainers.component.TestcontainersDriver;

public final class RedisTestcontainersDriver
    extends TestcontainersDriver<Runtime, Void, RedisComponent> {
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
