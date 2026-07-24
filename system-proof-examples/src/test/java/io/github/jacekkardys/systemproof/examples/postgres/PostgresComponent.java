package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Communication;
import io.github.jacekkardys.systemproof.model.ComponentFactory;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.endpoint.JdbcEndpoint;

final class PostgresComponent
    extends AbstractComponent<PostgresConfig.Runtime, DatabaseOperations> {

    @Communication.JdbcPostgresql
    private ProvidedPort<JdbcEndpoint> jdbc;

    private PostgresComponent() {}

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("postgres");
    }

    static PostgresComponent define(ComponentFactory components) {
        return components.create(
            PostgresComponent.class,
            PostgresConfig.class,
            PostgresTestcontainersDriver::new
        );
    }

    ProvidedPort<JdbcEndpoint> jdbc() {
        return jdbc;
    }
}
