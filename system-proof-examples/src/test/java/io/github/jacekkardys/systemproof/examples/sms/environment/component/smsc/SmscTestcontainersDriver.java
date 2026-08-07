package io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc;

import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;

import java.net.URI;
import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;
import io.github.jacekkardys.systemproof.examples.sms.environment.ReferenceImages;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.SmscConfig.Driver;
import io.github.jacekkardys.systemproof.endpoint.SmppEndpoint;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.testcontainers.component.ContainerPlan;
import io.github.jacekkardys.systemproof.testcontainers.component.PortBinding;
import io.github.jacekkardys.systemproof.testcontainers.component.StartedContainer;
import io.github.jacekkardys.systemproof.testcontainers.component.TestcontainersDriver;

public final class SmscTestcontainersDriver
    extends TestcontainersDriver<SmscConfig, UkarimSmscOperations, SmscComponent> {
    private final Driver configuration;

    public SmscTestcontainersDriver(@NonNull Driver configuration) {
        super(SmscComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(SmscComponent component, DriverContext context) {
        PortBinding smppPort = port(configuration.smppPort());
        PortBinding controlPort = port(configuration.controlPort());
        GenericContainer<?> container = referenceContainer()
            .waitingFor(new WaitAllStrategy()
                .withStrategy(Wait.forListeningPort())
                .withStrategy(Wait.forHttp("/").forPort(controlPort.port()).forStatusCode(200))
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

    private GenericContainer<?> referenceContainer() {
        if (ReferenceImages.SMSC.equals(configuration.image())) {
            return new GenericContainer<>(ReferenceImages.smsc());
        }
        return new GenericContainer<>(DockerImageName.parse(configuration.image()));
    }

    @Override
    protected UkarimSmscOperations createOperations(
        SmscComponent component,
        StartedContainer container,
        DriverContext context
    ) {
        URI controlEndpoint = container.external(component.control());
        return new UkarimSmscOperations(
            controlEndpoint,
            component.configuration().systemId(),
            () -> context.state(component).toString(),
            () -> context.componentEvents(component)
        );
    }

}
