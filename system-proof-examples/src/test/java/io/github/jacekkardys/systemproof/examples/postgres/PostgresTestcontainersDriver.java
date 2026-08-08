package io.github.jacekkardys.systemproof.examples.postgres;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.StartedContainer;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;
import org.testcontainers.utility.DockerImageName;

final class PostgresTestcontainersDriver
    extends TestcontainersDriver<
        PostgresConfig,
        DatabaseOperations,
        PostgresComponent
    > {

    private final PostgresConfig.Driver configuration;

    PostgresTestcontainersDriver(PostgresConfig.Driver configuration) {
        super(PostgresComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(PostgresComponent component, DriverContext context) {
        PostgresConfig componentConfiguration = component.configuration();
        PortBinding jdbcPort = port(configuration.jdbcPort());
        DockerImageName dockerImage = DockerImageName.parse(configuration.image())
            .asCompatibleSubstituteFor(configuration.compatibleImage());
        return ContainerPlan.container(dockerImage)
            .environment("POSTGRES_DB", componentConfiguration.database())
            .environment("POSTGRES_USER", componentConfiguration.username())
            .environment("POSTGRES_PASSWORD", componentConfiguration.password().reveal())
            .waitForListeningPorts(jdbcPort)
            .readinessTimeout(configuration.startupTimeout())
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
    protected DatabaseOperations createOperations(
        PostgresComponent component,
        StartedContainer container,
        DriverContext context
    ) {
        JdbcEndpoint endpoint = container.external(component.jdbc());
        return new DatabaseOperations(
            endpoint.url(),
            endpoint.username(),
            endpoint.password().reveal()
        );
    }
}
