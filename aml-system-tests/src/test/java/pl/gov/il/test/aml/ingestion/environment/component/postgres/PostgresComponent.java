package pl.gov.il.test.aml.ingestion.environment.component.postgres;

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
import pl.gov.il.test.harness.model.endpoint.JdbcEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PostgresComponent
    extends AbstractComponent<PostgresConfig.Runtime, AmlDatabaseOperations> {

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
