package pl.gov.il.test.aml.ingestion.environment.component.smsc;

import static pl.gov.il.test.harness.testcontainers.component.PortBinding.port;

import java.net.URI;
import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import pl.gov.il.test.aml.ingestion.environment.component.smsc.SmscConfig.Driver;
import pl.gov.il.test.aml.ingestion.environment.component.smsc.SmscConfig.Runtime;
import pl.gov.il.test.harness.model.endpoint.SmppEndpoint;
import pl.gov.il.test.harness.driver.DriverContext;
import pl.gov.il.test.harness.testcontainers.component.ContainerPlan;
import pl.gov.il.test.harness.testcontainers.component.PortBinding;
import pl.gov.il.test.harness.testcontainers.component.StartedContainer;
import pl.gov.il.test.harness.testcontainers.component.TestcontainersDriver;

public final class SmscTestcontainersDriver
    extends TestcontainersDriver<Runtime, SmscOperations, SmscComponent> {
    private final Driver configuration;

    public SmscTestcontainersDriver(@NonNull Driver configuration) {
        super(SmscComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(SmscComponent component, DriverContext context) {
        PortBinding smppPort = port(configuration.smppPort());
        PortBinding controlPort = port(configuration.controlPort());
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(configuration.image()))
            .withEnv(configuration.systemIdVariable(), component.configuration().systemId())
            .withEnv(configuration.passwordVariable(), component.configuration().password().reveal())
            .waitingFor(Wait.forHttp(configuration.healthPath())
                .forPort(controlPort.port())
                .forStatusCode(configuration.healthStatus())
                .withStartupTimeout(configuration.startupTimeout()));
        return ContainerPlan.container(container)
            .provides(
                component.smpp(),
                smppPort,
                address -> new SmppEndpoint(
                    address.host(),
                    address.port(),
                    component.configuration().systemId(),
                    component.configuration().password()
                )
            )
            .provides(
                component.control(),
                controlPort,
                component.configuration().controlPath(),
                address -> URI.create(address.value())
            )
            .build();
    }

    @Override
    protected SmscOperations createOperations(
        SmscComponent component,
        StartedContainer container,
        DriverContext context
    ) {
        URI sendEndpoint = container.external(component.control());
        return new SmscOperations(
            sendEndpoint,
            () -> context.state(component).toString(),
            () -> context.componentEvents(component)
        );
    }
}
