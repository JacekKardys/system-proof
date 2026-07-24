package pl.gov.il.test.aml.ingestion.environment.component.smsc;

import java.net.URI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.Accessors;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.Communication;
import pl.gov.il.test.harness.model.ComponentFactory;
import pl.gov.il.test.harness.model.ComponentType;
import pl.gov.il.test.harness.model.ProvidedPort;
import pl.gov.il.test.harness.model.endpoint.SmppEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SmscComponent extends AbstractComponent<SmscConfig.Runtime, SmscOperations> {

    @Communication.Smpp
    private ProvidedPort<SmppEndpoint> smpp;

    @Communication.Http
    private ProvidedPort<URI> control;

    public static SmscComponent define(@NonNull ComponentFactory components) {
        return components.create(
            SmscComponent.class,
            SmscConfig.class,
            SmscTestcontainersDriver::new
        );
    }

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("smsc");
    }
}
