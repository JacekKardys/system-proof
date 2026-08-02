package io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMS_DATABASE;
import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMS_INGESTION;

import java.net.URI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.communication.Communication;
import io.github.jacekkardys.systemproof.model.topology.PortContract;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;
import io.github.jacekkardys.systemproof.model.topology.StartupPrerequisite;
import io.github.jacekkardys.systemproof.model.component.SystemComponent;
import io.github.jacekkardys.systemproof.model.endpoint.JdbcEndpoint;

@SystemComponent(type = "ingestion", driver = SmsIngestionTestcontainersDriver.class)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SmsIngestionComponent extends AbstractComponent<SmsIngestionConfig, Void> {

    @PortContract(SMS_INGESTION)
    @Communication.Http
    private ProvidedPort<URI> sms;

    @StartupPrerequisite
    @PortContract(SMS_DATABASE)
    @Communication.JdbcPostgresql
    private RequiredPort<JdbcEndpoint> jdbc;

}
