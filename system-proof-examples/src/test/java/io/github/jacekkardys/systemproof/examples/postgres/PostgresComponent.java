package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.communication.Communication;
import io.github.jacekkardys.systemproof.model.topology.PortContract;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.component.SystemComponent;
import io.github.jacekkardys.systemproof.model.endpoint.JdbcEndpoint;

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
