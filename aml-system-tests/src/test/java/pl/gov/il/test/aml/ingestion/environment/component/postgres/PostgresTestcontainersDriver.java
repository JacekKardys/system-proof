package pl.gov.il.test.aml.ingestion.environment.component.postgres;

import static pl.gov.il.test.harness.testcontainers.component.PortBinding.port;

import lombok.NonNull;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import pl.gov.il.test.aml.ingestion.environment.component.postgres.PostgresConfig.Driver;
import pl.gov.il.test.aml.ingestion.environment.component.postgres.PostgresConfig.Runtime;
import pl.gov.il.test.harness.model.endpoint.JdbcEndpoint;
import pl.gov.il.test.harness.driver.DriverContext;
import pl.gov.il.test.harness.testcontainers.component.ContainerPlan;
import pl.gov.il.test.harness.testcontainers.component.PortBinding;
import pl.gov.il.test.harness.testcontainers.component.StartedContainer;
import pl.gov.il.test.harness.testcontainers.component.TestcontainersDriver;

public final class PostgresTestcontainersDriver
    extends TestcontainersDriver<Runtime, AmlDatabaseOperations, PostgresComponent> {
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
    protected AmlDatabaseOperations createOperations(
        PostgresComponent component,
        StartedContainer container,
        DriverContext context
    ) {
        JdbcEndpoint endpoint = container.external(component.jdbc());
        return new AmlDatabaseOperations(
            endpoint.url(),
            endpoint.username(),
            endpoint.password().reveal(),
            () -> context.componentEvents(component)
        );
    }
}
