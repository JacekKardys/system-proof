package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import lombok.NonNull;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.PostgresConfig.Driver;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.PostgresConfig.Runtime;
import io.github.jacekkardys.systemproof.model.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.StartedContainer;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;

public final class PostgresTestcontainersDriver
    extends TestcontainersDriver<Runtime, SmsDatabaseOperations, PostgresComponent> {
    private final Driver configuration;

    public PostgresTestcontainersDriver(@NonNull Driver configuration) {
        super(PostgresComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(PostgresComponent component, DriverContext context) {
        Runtime componentConfiguration = component.configuration();
        PortBinding jdbcPort = port(configuration.jdbcPort());
        DockerImageName dockerImage = DockerImageName.parse(configuration.image())
            .asCompatibleSubstituteFor(configuration.compatibleImage());
        var container = new PostgreSQLContainer<>(dockerImage)
            .withDatabaseName(componentConfiguration.database())
            .withUsername(componentConfiguration.username())
            .withPassword(componentConfiguration.password().reveal())
            .withStartupTimeout(configuration.startupTimeout());
        return ContainerPlan.container(container)
            .provides(
                component.jdbc(),
                jdbcPort,
                "/" + componentConfiguration.database(),
                address -> new JdbcEndpoint(
                    address.value(),
                    componentConfiguration.username(),
                    componentConfiguration.password()
                )
            )
            .build();
    }

    @Override
    protected SmsDatabaseOperations createOperations(
        PostgresComponent component,
        StartedContainer container,
        DriverContext context
    ) {
        JdbcEndpoint endpoint = container.external(component.jdbc());
        return new SmsDatabaseOperations(
            endpoint.url(),
            endpoint.username(),
            endpoint.password().reveal(),
            () -> context.componentEvents(component)
        );
    }
}
