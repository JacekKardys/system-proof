package pl.gov.il.test.aml.ingestion.environment.component.ingestion;

import static pl.gov.il.test.harness.testcontainers.component.PortBinding.port;

import java.net.URI;
import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import pl.gov.il.test.aml.ingestion.environment.component.ingestion.SmsIngestionConfig.Driver;
import pl.gov.il.test.aml.ingestion.environment.component.ingestion.SmsIngestionConfig.Runtime;
import pl.gov.il.test.harness.model.endpoint.JdbcEndpoint;
import pl.gov.il.test.harness.driver.DriverContext;
import pl.gov.il.test.harness.testcontainers.component.ContainerPlan;
import pl.gov.il.test.harness.testcontainers.component.PortBinding;
import pl.gov.il.test.harness.testcontainers.component.TestcontainersDriver;

public final class SmsIngestionTestcontainersDriver
    extends TestcontainersDriver<Runtime, Void, SmsIngestionComponent> {
    private final Driver configuration;

    public SmsIngestionTestcontainersDriver(@NonNull Driver configuration) {
        super(SmsIngestionComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(SmsIngestionComponent component, DriverContext context) {
        JdbcEndpoint database = context.resolve(component.jdbc());
        PortBinding httpPort = port(configuration.httpPort());
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(configuration.image()))
            .withEnv(configuration.databaseUrlVariable(), database.url())
            .withEnv(configuration.databaseUsernameVariable(), database.username())
            .withEnv(configuration.databasePasswordVariable(), database.password().reveal())
            .waitingFor(Wait.forHttp(configuration.readinessPath())
                .forPort(httpPort.port())
                .forStatusCode(configuration.readinessStatus())
                .withStartupTimeout(configuration.startupTimeout()));
        return ContainerPlan.container(container)
            .provides(
                component.sms(),
                httpPort,
                component.configuration().smsPath(),
                address -> URI.create(address.value())
            )
            .build();
    }
}
