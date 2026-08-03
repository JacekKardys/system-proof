package io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import java.net.URI;
import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.examples.sms.environment.ReferenceImages;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion.SmsIngestionConfig.Driver;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;

public final class SmsIngestionTestcontainersDriver
    extends TestcontainersDriver<SmsIngestionConfig, Void, SmsIngestionComponent> {
    private final Driver configuration;

    public SmsIngestionTestcontainersDriver(@NonNull Driver configuration) {
        super(SmsIngestionComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(SmsIngestionComponent component, DriverContext context) {
        JdbcEndpoint database = context.resolve(component.jdbc());
        PortBinding httpPort = port(configuration.httpPort());
        GenericContainer<?> container = referenceContainer()
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

    private GenericContainer<?> referenceContainer() {
        if (ReferenceImages.INGESTION.equals(configuration.image())) {
            return new GenericContainer<>(ReferenceImages.ingestion());
        }
        return new GenericContainer<>(DockerImageName.parse(configuration.image()));
    }
}
