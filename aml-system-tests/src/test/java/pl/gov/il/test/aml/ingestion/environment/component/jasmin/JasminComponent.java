package pl.gov.il.test.aml.ingestion.environment.component.jasmin;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.Communication;
import pl.gov.il.test.harness.model.ComponentFactory;
import pl.gov.il.test.harness.model.ComponentType;
import pl.gov.il.test.harness.model.ProvidedPort;
import pl.gov.il.test.harness.model.RequiredPort;
import pl.gov.il.test.harness.model.StartupPrerequisite;
import pl.gov.il.test.harness.model.endpoint.AmqpEndpoint;
import pl.gov.il.test.harness.model.endpoint.RedisEndpoint;
import pl.gov.il.test.harness.model.endpoint.SmppEndpoint;

@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JasminComponent extends AbstractComponent<JasminConfig.Runtime, Void> {

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    public enum SmppBindMode {
        TRANSCEIVER("transceiver", "BOUND_TRX"),
        RECEIVER("receiver", "BOUND_RX");

        private final String jasminValue;
        private final String boundState;

        public static SmppBindMode parse(@NonNull String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "Unsupported SMPP bind mode for deliver_sm reception: " + value,
                    exception
                );
            }
        }
    }

    @StartupPrerequisite
    @Communication.Smpp
    private RequiredPort<SmppEndpoint> smpp;

    @StartupPrerequisite
    @Communication.Http
    private RequiredPort<URI> sms;

    @StartupPrerequisite
    @Communication.Amqp
    private RequiredPort<AmqpEndpoint> amqp;

    @StartupPrerequisite
    @Communication.Redis
    private RequiredPort<RedisEndpoint> redis;

    @Communication.Tcp
    private ProvidedPort<InetSocketAddress> administration;

    public static JasminComponent define(@NonNull ComponentFactory components) {
        return components.create(
            JasminComponent.class,
            JasminConfig.class,
            JasminTestcontainersDriver::new
        );
    }

    @Override
    protected ComponentType componentType() {
        return ComponentType.of("jasmin");
    }
}
