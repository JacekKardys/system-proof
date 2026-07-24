package io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc;

import java.net.URI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Communication;
import io.github.jacekkardys.systemproof.model.ComponentFactory;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.endpoint.SmppEndpoint;

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
