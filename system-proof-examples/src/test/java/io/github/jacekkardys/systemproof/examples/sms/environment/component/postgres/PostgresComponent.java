package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

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
import io.github.jacekkardys.systemproof.model.endpoint.JdbcEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PostgresComponent
    extends AbstractComponent<PostgresConfig.Runtime, SmsDatabaseOperations> {

    @Communication.JdbcPostgresql
    private ProvidedPort<JdbcEndpoint> jdbc;

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("postgres");
    }

    public static PostgresComponent define(@NonNull ComponentFactory components) {
        return components.create(
            PostgresComponent.class,
            PostgresConfig.class,
            PostgresTestcontainersDriver::new
        );
    }

    public static PostgresComponent container(
        String qualifier,
        PostgresConfig.Runtime configuration,
        PostgresConfig.Driver driverConfiguration
    ) {
        return ComponentFactory.create(
            PostgresComponent.class,
            qualifier,
            configuration,
            new PostgresTestcontainersDriver(driverConfiguration)
        );
    }
}
