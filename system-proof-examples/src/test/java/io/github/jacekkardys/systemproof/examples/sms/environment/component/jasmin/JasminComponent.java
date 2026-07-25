package io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin;

import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_ADMINISTRATION;
import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_AMQP;
import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.JASMIN_REDIS;
import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMSC_SMPP;
import static io.github.jacekkardys.systemproof.examples.sms.environment.SmsContractIds.SMS_INGESTION;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Communication;
import io.github.jacekkardys.systemproof.model.ComponentFactory;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.PortContract;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;
import io.github.jacekkardys.systemproof.model.StartupPrerequisite;
import io.github.jacekkardys.systemproof.model.endpoint.AmqpEndpoint;
import io.github.jacekkardys.systemproof.model.endpoint.RedisEndpoint;
import io.github.jacekkardys.systemproof.model.endpoint.SmppEndpoint;

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
    @PortContract(SMSC_SMPP)
    @Communication.Smpp
    private RequiredPort<SmppEndpoint> smpp;

    @StartupPrerequisite
    @PortContract(SMS_INGESTION)
    @Communication.Http
    private RequiredPort<URI> sms;

    @StartupPrerequisite
    @PortContract(JASMIN_AMQP)
    @Communication.Amqp
    private RequiredPort<AmqpEndpoint> amqp;

    @StartupPrerequisite
    @PortContract(JASMIN_REDIS)
    @Communication.Redis
    private RequiredPort<RedisEndpoint> redis;

    @PortContract(JASMIN_ADMINISTRATION)
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
