package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.communication.Communication;
import io.github.jacekkardys.systemproof.topology.PortContract;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.component.SystemComponent;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;

@SystemComponent(type = "postgres", driver = PostgresTestcontainersDriver.class)
final class PostgresComponent
    extends AbstractComponent<PostgresConfig, DatabaseOperations> {

    @PortContract("jdbc")
    @Communication.JdbcPostgresql
    private ProvidedPort<JdbcEndpoint> jdbc;

    private PostgresComponent() {}

    ProvidedPort<JdbcEndpoint> jdbc() {
        return jdbc;
    }
}
