package pl.gov.il.test.aml.ingestion.environment.component.ingestion;

import java.net.URI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.Communication;
import pl.gov.il.test.harness.model.ComponentFactory;
import pl.gov.il.test.harness.model.ComponentType;
import pl.gov.il.test.harness.model.ProvidedPort;
import pl.gov.il.test.harness.model.RequiredPort;
import pl.gov.il.test.harness.model.StartupPrerequisite;
import pl.gov.il.test.harness.model.endpoint.JdbcEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SmsIngestionComponent extends AbstractComponent<SmsIngestionConfig.Runtime, Void> {

    @Communication.Http
    private ProvidedPort<URI> sms;

    @StartupPrerequisite
    @Communication.JdbcPostgresql
    private RequiredPort<JdbcEndpoint> jdbc;

    public static SmsIngestionComponent define(@NonNull ComponentFactory components) {
        return components.create(
            SmsIngestionComponent.class,
            SmsIngestionConfig.class,
            SmsIngestionTestcontainersDriver::new
        );
    }

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("ingestion");
    }
}
