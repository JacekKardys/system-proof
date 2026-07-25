package io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMSC_CONTROL;
import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMSC_SMPP;

import java.net.URI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Communication;
import io.github.jacekkardys.systemproof.model.PortContract;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.SystemComponent;
import io.github.jacekkardys.systemproof.model.endpoint.SmppEndpoint;

@SystemComponent(
    type = "system-proof-smsc-simulator",
    driver = SmscTestcontainersDriver.class
)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SmscComponent extends AbstractComponent<SmscConfig, UkarimSmscOperations> {

    @PortContract(SMSC_SMPP)
    @Communication.Smpp
    private ProvidedPort<SmppEndpoint> smpp;

    @PortContract(SMSC_CONTROL)
    @Communication.Http
    private ProvidedPort<URI> control;

}
