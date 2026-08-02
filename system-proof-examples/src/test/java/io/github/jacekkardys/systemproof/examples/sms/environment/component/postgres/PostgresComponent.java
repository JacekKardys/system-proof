package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMS_DATABASE;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.communication.Communication;
import io.github.jacekkardys.systemproof.model.topology.PortContract;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.component.SystemComponent;
import io.github.jacekkardys.systemproof.model.endpoint.JdbcEndpoint;

@SystemComponent(type = "postgres", driver = PostgresTestcontainersDriver.class)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PostgresComponent
    extends AbstractComponent<PostgresConfig, SmsDatabaseOperations> {

    @PortContract(SMS_DATABASE)
    @Communication.JdbcPostgresql
    private ProvidedPort<JdbcEndpoint> jdbc;

}
